package dev.logast.export;

import com.bertramlabs.plugins.hcl4j.HCLParser;
import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * #166: a Terragrunt/Terraform repo's resources per environment, read without the CLIs (no state, no credentials).
 * Each Terragrunt unit ({@code terragrunt.hcl} with a {@code terraform.source}) names a module; its {@code inputs}
 * (over an included root file's) and the module's variable defaults evaluate the module's resources, so names built
 * from variables and locals come out as the environment has them. A Key Vault secret is {@code secret:<vault>/<name>},
 * the id the Helm facts use for what a workload mounts.
 */
public final class TerraformExtractor implements Extractor {
    public static final String ID = "terraform";
    private static final Pattern MODULE_SOURCE = Pattern.compile("(?:^|/)terraform//?([^/?\"]+)|//([^/?\"]+)$");
    private static final Pattern REFERENCE = Pattern.compile("^(?:data\\.)?([a-z0-9_]+)\\.([A-Za-z0-9_-]+)\\.(?:id|name)$");
    private static final List<String> KEEP = List.of("location", "sku_name", "version", "content_type", "expiration_date",
            "account_tier", "storage_account_name", "server_name", "resource_group_name");

    private final List<String> problems = new ArrayList<>();

    @Override
    public String id() {
        return ID;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Whether the repo has Terragrunt units. */
    public static boolean applies(Path root) {
        return !units(root).isEmpty();
    }

    /** Every {@code terragrunt.hcl} with a {@code terraform} block (a unit, not a file others include). */
    static List<Path> units(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().equals("terragrunt.hcl") && !p.toString().contains("/.terragrunt-cache/")
                    && !p.toString().contains("/.git/") && read(p).matches("(?s).*\\bterraform\\s*\\{.*")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code terragrunt/<subscription>/<region>/<env>/…}: the segment after the region; else the unit's parent folder. */
    static String env(Path root, Path unit) {
        List<String> segs = new ArrayList<>();
        root.toAbsolutePath().normalize().relativize(unit.getParent().toAbsolutePath().normalize()).forEach(p -> segs.add(p.toString()));
        int tg = segs.indexOf("terragrunt");
        List<String> rest = tg >= 0 ? segs.subList(tg + 1, segs.size()) : segs;
        return rest.size() >= 3 ? rest.get(2) : rest.isEmpty() ? "default" : rest.getLast();
    }

    @Override
    public void extract(Path root, FactSink out) {
        for (Path unit : units(root)) {
            String env = env(root, unit);
            try {
                unit(root, unit, env, out);
            } catch (IOException | RuntimeException e) {
                problems.add(rel(root, unit) + ": " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void unit(Path root, Path unit, String env, FactSink out) throws IOException {
        Map<String, Object> tg = parse(read(unit), Map.of());
        Map<String, Object> inputs = new LinkedHashMap<>();
        Path included = included(unit);
        if (included != null) {
            Object in = parse(read(included), Map.of()).get("inputs");
            if (in instanceof Map<?, ?> m) {
                inputs.putAll((Map<String, Object>) m);
            }
        }
        if (tg.get("inputs") instanceof Map<?, ?> m) {
            inputs.putAll((Map<String, Object>) m);
        }
        String source = tg.get("terraform") instanceof Map<?, ?> t ? String.valueOf(t.get("source")) : null;
        Path module = module(root, unit, source);
        String unitId = "tf-unit:" + rel(root, unit.getParent());
        Map<String, Object> ua = new TreeMap<>();
        ua.put("env", env);
        if (module != null) {
            ua.put("module", rel(root, module));
        }
        out.node(new Node(unitId, "tf-unit", env, ua, Evidence.at(rel(root, unit), null)));
        if (module == null) {
            problems.add(rel(root, unit) + ": no module folder for source " + source);
            return;
        }
        module(root, module, inputs, env, unitId, 0, out);
    }

    @SuppressWarnings("unchecked")
    private void module(Path root, Path dir, Map<String, Object> inputs, String env, String unitId, int depth, FactSink out) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().endsWith(".tf")).sorted().toList();
        }
        StringBuilder all = new StringBuilder();
        for (Path f : files) {
            all.append(read(f)).append('\n');
        }
        // first pass: the variables' defaults; then every variable set (input over default), so locals resolve too
        Map<String, Object> vars = new LinkedHashMap<>();
        if (parse(all.toString(), Map.of()).get("variable") instanceof Map<?, ?> vs) {
            ((Map<String, Object>) vs).forEach((name, def) -> {
                if (def instanceof Map<?, ?> d && d.get("default") != null) {
                    vars.put(name, d.get("default"));
                }
            });
        }
        vars.putAll(inputs);
        Map<String, Object> parsed = parse(all.toString(), vars);
        Map<String, Map<String, Object>> resources = parsed.get("resource") instanceof Map<?, ?> r ? (Map<String, Map<String, Object>>) r : Map.of();
        Map<String, Map<String, Object>> data = parsed.get("data") instanceof Map<?, ?> d ? (Map<String, Map<String, Object>>) d : Map.of();
        for (var type : resources.entrySet()) {
            for (var block : type.getValue().entrySet()) {
                Map<String, Object> body = block.getValue() instanceof Map<?, ?> b ? (Map<String, Object>) b : Map.of();
                Evidence at = blockLine(root, files, "resource", type.getKey(), block.getKey());
                String name = known(body.get("name")) ? String.valueOf(body.get("name")) : block.getKey();
                if (type.getKey().equals("azurerm_key_vault_secret")) {
                    String vault = vaultName(String.valueOf(body.get("key_vault_id")), resources, data);
                    Map<String, Object> attrs = new TreeMap<>();
                    attrs.put("vault", vault);
                    attrs.put("object", name);
                    attrs.put("managedBy", "terraform");
                    for (String k : List.of("content_type", "expiration_date", "not_before_date")) {
                        if (known(body.get(k))) {
                            attrs.put(k, String.valueOf(body.get(k)));
                        }
                    }
                    if (body.get("tags") instanceof Map<?, ?> tags) {
                        attrs.put("tags", new TreeMap<>((Map<String, Object>) tags));
                    }
                    String id = "secret:" + vault + "/" + name;
                    out.node(new Node(id, "secret", env, attrs, at));
                    out.edge(Edge.extracted("resource:azurerm_key_vault:" + vault, "has", id, null, at).inEnv(env));
                    out.edge(Edge.extracted(unitId, "defines", id, null, at).inEnv(env));
                    continue;
                }
                Map<String, Object> attrs = new TreeMap<>();
                attrs.put("type", type.getKey());
                attrs.put("label", block.getKey());
                attrs.put("name", name);
                for (String k : KEEP) {
                    if (known(body.get(k))) {
                        attrs.put(k, String.valueOf(body.get(k)));
                    }
                }
                if (body.get("tags") instanceof Map<?, ?> tags) {
                    attrs.put("tags", new TreeMap<>((Map<String, Object>) tags));
                }
                String id = "resource:" + type.getKey() + ":" + name;
                out.node(new Node(id, "resource", env, attrs, at));
                out.edge(Edge.extracted(unitId, "defines", id, null, at).inEnv(env));
            }
        }
        if (depth == 0 && parsed.get("module") instanceof Map<?, ?> modules) {   // one level of local submodules
            for (var m : ((Map<String, Object>) modules).entrySet()) {
                if (!(m.getValue() instanceof Map<?, ?> body)) {
                    continue;
                }
                String src = String.valueOf(body.get("source"));
                if (!src.startsWith("./") && !src.startsWith("../")) {
                    continue;
                }
                Map<String, Object> args = new LinkedHashMap<>((Map<String, Object>) body);
                args.remove("source");
                Path sub = dir.resolve(src).normalize();
                if (Files.isDirectory(sub)) {
                    module(root, sub, args, env, unitId, depth + 1, out);
                }
            }
        }
    }

    /** {@code azurerm_key_vault.main.id} → that vault's name (a resource or a data source of the module). */
    @SuppressWarnings("unchecked")
    private static String vaultName(String ref, Map<String, Map<String, Object>> resources, Map<String, Map<String, Object>> data) {
        Matcher m = REFERENCE.matcher(ref.strip());
        if (m.matches()) {
            for (Map<String, Map<String, Object>> blocks : List.of(resources, data)) {
                Map<String, Object> of = blocks.get(m.group(1));
                if (of != null && of.get(m.group(2)) instanceof Map<?, ?> b && known(((Map<String, Object>) b).get("name"))) {
                    return String.valueOf(((Map<String, Object>) b).get("name"));
                }
            }
        }
        return "vault";
    }

    /** A value hcl4j evaluated: not null, not a reference or a type it left as an object. */
    private static boolean known(Object v) {
        if (v == null) {
            return false;
        }
        String s = String.valueOf(v);
        return !s.isBlank() && !s.contains("Attribute:") && !s.contains("PrimitiveType:") && !s.matches("^[a-z0-9_]+\\.[A-Za-z0-9_.\\[\\]-]+$");
    }

    private static Map<String, Object> parse(String hcl, Map<String, Object> vars) throws IOException {
        HCLParser p = new HCLParser();
        if (!vars.isEmpty()) {
            p.setVariables(new LinkedHashMap<>(vars));
        }
        try {
            return p.parse(hcl, true);
        } catch (com.bertramlabs.plugins.hcl4j.HCLParserException e) {
            throw new IOException("HCL: " + e.getMessage(), e);
        }
    }

    /** The file a unit includes ({@code find_in_parent_folders("root.hcl")} and the like): the nearest one up. */
    private static Path included(Path unit) {
        Matcher m = Pattern.compile("find_in_parent_folders\\(\\s*\"([^\"]+)\"").matcher(read(unit));
        String name = m.find() ? m.group(1) : read(unit).contains("find_in_parent_folders()") ? "terragrunt.hcl" : null;
        if (name == null) {
            return null;
        }
        for (Path d = unit.getParent().getParent(); d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve(name))) {
                return d.resolve(name);
            }
        }
        return null;
    }

    /** The module a unit's source names: {@code …/terraform//<module>} or {@code …/terraform/<module>}, under the repo. */
    static Path module(Path root, Path unit, String source) {
        if (source == null || source.equals("null")) {
            return null;
        }
        if (!source.contains("${") && (source.startsWith("./") || source.startsWith("../"))) {
            Path p = unit.getParent().resolve(source.replace("//", "/")).normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        Matcher m = MODULE_SOURCE.matcher(source.replaceAll("\\?.*$", ""));
        if (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            Path p = root.resolve("terraform").resolve(name);
            if (Files.isDirectory(p)) {
                return p;
            }
        }
        return null;
    }

    private static Evidence blockLine(Path root, List<Path> files, String block, String type, String label) {
        Pattern p = Pattern.compile("^\\s*" + block + "\\s+\"" + Pattern.quote(type) + "\"\\s+\"" + Pattern.quote(label) + "\"");
        for (Path f : files) {
            List<String> lines = read(f).lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                if (p.matcher(lines.get(i)).find()) {
                    return Evidence.at(rel(root, f), i + 1);
                }
            }
        }
        return Evidence.at(files.isEmpty() ? "." : rel(root, files.getFirst()), null);
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String rel(Path root, Path p) {
        return root.toAbsolutePath().normalize().relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }
}
