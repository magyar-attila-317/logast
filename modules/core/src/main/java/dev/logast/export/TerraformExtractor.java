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

    private final List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
    private final boolean useTerragrunt;
    private final String terragrunt;

    /** Inputs from {@code terragrunt render} when terragrunt is on the PATH (#175), else from the HCL alone. */
    public TerraformExtractor() {
        this(terragruntAvailable());
    }

    public TerraformExtractor(boolean useTerragrunt) {
        this(useTerragrunt, binary());
    }

    /** @param terragrunt the terragrunt binary to run */
    TerraformExtractor(boolean useTerragrunt, String terragrunt) {
        this.useTerragrunt = useTerragrunt;
        this.terragrunt = terragrunt;
    }

    /** The terragrunt to run: {@code LOGAST_TERRAGRUNT} (a path, for a version other than the PATH's), else {@code terragrunt}. */
    static String binary() {
        String b = System.getenv("LOGAST_TERRAGRUNT");
        return b == null || b.isBlank() ? "terragrunt" : b;
    }

    static boolean terragruntAvailable() {
        if ("off".equalsIgnoreCase(System.getenv("LOGAST_TERRAGRUNT"))) {
            return false;   // #185: the HCL alone
        }
        try {
            Process p = new ProcessBuilder(binary(), "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The unit's resolved config, in the unit's folder: {@code terragrunt render --format json} (1.x and late 0.x), else
     * {@code terragrunt render-json --terragrunt-json-out <temp file>} (older setups); null when both fail (then the HCL
     * is read). Neither writes into the repo.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> render(Path unit) {
        String last = null;
        if (timeouts.get() >= MAX_TIMEOUTS) {
            return null;   // #185: terragrunt hung on several units already; the rest are read from their HCL
        }
        try {
            Run r = run(unit.getParent(), List.of(terragrunt, "render", "--format", "json", "--non-interactive"));
            if (r.ok() && r.out().indexOf('{') >= 0) {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(r.out().substring(r.out().indexOf('{')), Map.class);
            }
            last = r.err();
            if (r.timedOut()) {
                timedOut(unit);
                return null;
            }
            Path json = Files.createTempFile("logast-terragrunt", ".json");
            Run old = run(unit.getParent(), List.of(terragrunt, "render-json", "--terragrunt-json-out", json.toString(), "--terragrunt-non-interactive"));
            String text = Files.readString(json);
            Files.deleteIfExists(json);
            if (old.ok() && text.indexOf('{') >= 0) {
                return new com.fasterxml.jackson.databind.ObjectMapper().readValue(text.substring(text.indexOf('{')), Map.class);
            }
            last = old.err();
            if (old.timedOut()) {
                timedOut(unit);
                return null;
            }
        } catch (IOException e) {
            last = e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        problems.add(unit.getParent().getFileName() + ": terragrunt could not render it, read the HCL instead: "
                + (last == null ? "" : last.replaceAll("\\u001b\\[[0-9;]*m", "").lines().filter(l -> !l.isBlank()).reduce((a, b) -> b).orElse("")));
        return null;
    }

    private record Run(boolean ok, String out, String err, boolean timedOut) {
    }

    static final int MAX_TIMEOUTS = 3;
    static volatile Long timeoutForTests;
    private final java.util.concurrent.atomic.AtomicInteger timeouts = new java.util.concurrent.atomic.AtomicInteger();

    private void timedOut(Path unit) {
        int n = timeouts.incrementAndGet();
        problems.add(unit.getParent().getFileName() + ": terragrunt did not finish within " + timeout() + " s (a dependency's remote "
                + "state, credentials?), read the HCL instead");
        if (n == MAX_TIMEOUTS) {
            problems.add("terragrunt timed out on " + n + " units: the remaining units are read from their HCL "
                    + "(LOGAST_TERRAGRUNT_TIMEOUT=<seconds> waits longer, LOGAST_TERRAGRUNT=off never runs it)");
        }
    }

    /** #185: seconds a unit's render may take ({@code LOGAST_TERRAGRUNT_TIMEOUT}, default 60). */
    static long timeout() {
        if (timeoutForTests != null) {
            return timeoutForTests;
        }
        try {
            return Long.parseLong(System.getenv().getOrDefault("LOGAST_TERRAGRUNT_TIMEOUT", "60").strip());
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    /** #185: output goes to files, so the timeout applies to the process, whatever it does with its output. */
    private static Run run(Path dir, List<String> cmd) throws IOException, InterruptedException {
        Path out = Files.createTempFile("logast-terragrunt", ".out");
        Path err = Files.createTempFile("logast-terragrunt", ".err");
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectOutput(out.toFile()).redirectError(err.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null")))
                    .start();
            boolean done = p.waitFor(timeout(), java.util.concurrent.TimeUnit.SECONDS);
            if (!done) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return new Run(false, "", "timeout", true);
            }
            return new Run(p.exitValue() == 0, Files.readString(out), Files.readString(err), false);
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** #185: units rendered at a time ({@code LOGAST_TERRAGRUNT_THREADS}, default 4). */
    static int threads() {
        try {
            return Math.max(1, Integer.parseInt(System.getenv().getOrDefault("LOGAST_TERRAGRUNT_THREADS", "4").strip()));
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    /** Whether the repo has Terragrunt units. */
    public static boolean applies(Path root) {
        return !units(root).isEmpty();
    }

    /** Every {@code terragrunt.hcl} with a {@code terraform} block (a unit, not a file others include). */
    static List<Path> units(Path root) {
        List<Path> all;
        try (Stream<Path> s = Files.walk(root)) {
            all = s.filter(p -> p.getFileName().toString().equals("terragrunt.hcl") && !p.toString().contains("/.terragrunt-cache/")
                    && !p.toString().contains("/.git/")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // a leaf: no other terragrunt.hcl below it (the classic root terragrunt.hcl, with remote_state and shared
        // extra_arguments, has the units below it and is only included); and it, or what it includes, names a source
        return all.stream().filter(p -> all.stream().noneMatch(q -> !q.equals(p) && q.getParent().startsWith(p.getParent())))
                .filter(p -> SOURCE.matcher(read(p)).find() || included(p) != null && SOURCE.matcher(read(included(p))).find())
                .toList();
    }

    private static final Pattern SOURCE = Pattern.compile("(?s)\\bterraform\\s*\\{[^}]*\\bsource\\s*=");
    /** Folder names that are an environment, whatever the layout puts around them. */
    private static final Pattern ENV_NAME = Pattern.compile("(?i)dev|develop|development|test|tst|qa|int|integration|ppe|uat|acc|"
            + "acceptance|stage|staging|stg|preprod|pre-prod|prod|production|prd|rtl|sandbox|sbx");

    /** {@code terragrunt/<subscription>/<region>/<env>/…}: the segment after the region; else the unit's parent folder. */
    static String env(Path root, Path unit) {
        List<String> segs = new ArrayList<>();
        root.toAbsolutePath().normalize().relativize(unit.getParent().toAbsolutePath().normalize()).forEach(p -> segs.add(p.toString()));
        for (String seg : segs) {   // a folder named like an environment, in any layout
            if (ENV_NAME.matcher(seg).matches()) {
                return seg;
            }
        }
        int tg = segs.indexOf("terragrunt");
        List<String> rest = tg >= 0 ? segs.subList(tg + 1, segs.size()) : segs;
        return rest.size() >= 3 ? rest.get(2) : rest.isEmpty() ? "default" : rest.getLast();
    }

    @Override
    public void extract(Path root, FactSink out) {
        List<Path> units = units(root);
        // #185: the renders (each a terragrunt process) run side by side, with progress; the facts are written in order
        Map<Path, java.util.concurrent.Future<Map<String, Object>>> renders = new LinkedHashMap<>();
        var pool = useTerragrunt ? java.util.concurrent.Executors.newFixedThreadPool(threads()) : null;
        if (pool != null) {
            for (Path unit : units) {
                renders.put(unit, pool.submit(() -> render(unit)));
            }
            pool.shutdown();
        }
        int done = 0;
        for (Path unit : units) {
            String env = env(root, unit);
            try {
                Map<String, Object> rendered = null;
                if (pool != null) {
                    // #245: progress from this thread (the renders' threads would print between a fleet's repo lines)
                    System.err.println("… terragrunt render " + rel(root, unit) + " (" + ++done + "/" + units.size() + ")");
                    try {
                        rendered = renders.get(unit).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.ExecutionException e) {
                        problems.add(rel(root, unit) + ": " + e.getCause());
                    }
                }
                unit(root, unit, env, rendered, out);
            } catch (IOException | RuntimeException e) {
                problems.add(rel(root, unit) + ": " + e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void unit(Path root, Path unit, String env, Map<String, Object> rendered, FactSink out) throws IOException {
        Map<String, Object> inputs = new LinkedHashMap<>();
        String source;
        String inputsFrom;
        if (rendered != null) {   // #175: Terragrunt evaluates its own functions (get_env, get_terragrunt_dir, …)
            if (rendered.get("inputs") instanceof Map<?, ?> m) {
                inputs.putAll((Map<String, Object>) m);
            }
            source = rendered.get("terraform") instanceof Map<?, ?> t && t.get("source") != null ? String.valueOf(t.get("source")) : null;
            inputsFrom = "terragrunt";
        } else {
            Map<String, Object> tg = parse(read(unit), Map.of());
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
            source = tg.get("terraform") instanceof Map<?, ?> t ? String.valueOf(t.get("source")) : null;
            inputsFrom = "hcl";
        }
        Path module = module(root, unit, source);
        String unitId = "tf-unit:" + rel(root, unit.getParent());
        Map<String, Object> ua = new TreeMap<>();
        ua.put("env", env);
        ua.put("inputsFrom", inputsFrom);
        if (module != null) {
            ua.put("module", rel(root, module));
        }
        out.node(new Node(unitId, "tf-unit", env, ua, Evidence.at(rel(root, unit), null)));
        if (module == null) {
            problems.add(rel(root, unit) + (source != null && source.matches("^(git::|github\\.com|https?://|s3::|gcs::|tfr://).*")
                    ? ": the module is in another repo (" + source + "): its resources are not read"
                    : ": no module folder for source " + source));
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
                        attrs.put("tags", cleanTags((Map<String, Object>) tags));
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
                        attrs.put(k, dev.logast.facts.Secrets.clean(k, String.valueOf(body.get(k))));   // #183
                    }
                }
                if (body.get("tags") instanceof Map<?, ?> tags) {
                    attrs.put("tags", cleanTags((Map<String, Object>) tags));
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

    /** #183: tags as facts may carry them: a value that looks like a secret is redacted. */
    private static Map<String, Object> cleanTags(Map<String, Object> tags) {
        Map<String, Object> out = new TreeMap<>();
        tags.forEach((k, v) -> out.put(k, v == null ? null : dev.logast.facts.Secrets.clean(k, String.valueOf(v))));
        return out;
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
            return dev.logast.QuietStdout.call(() -> p.parse(hcl, true));   // #245: hcl4j prints its debug lines to stdout
        } catch (com.bertramlabs.plugins.hcl4j.HCLParserException e) {
            throw new IOException("HCL: " + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
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
        String plain = source.replaceAll("\\?.*$", "").replace("//", "/");
        if (plain.startsWith("/") && Files.isDirectory(Path.of(plain))) {   // terragrunt render resolves the path
            return Path.of(plain).normalize();
        }
        java.util.regex.Matcher parent = Pattern.compile("\\$\\{get_(?:parent_terragrunt_dir|repo_root)\\([^)]*\\)}/?(.*)").matcher(plain);
        if (parent.find()) {   // the HCL alone: the repo root and the included root file's folder are where the modules are
            for (Path base : List.of(root, included(unit) == null ? root : included(unit).getParent())) {
                Path p = base.resolve(parent.group(1)).normalize();
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
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
