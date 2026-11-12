package dev.logast;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** The file-writing parts of {@code logast init}: a starter policy, MDC vocabulary suggestions, .gitignore entries. */
public final class ServiceSetup {
    private ServiceSetup() {
    }

    public static final List<String> GITIGNORE = List.of(".logast/*", "!.logast/baseline.json", "!.logast/traffic.yaml");
    private static final Pattern MDC_KEY = Pattern.compile(
            "(?:MDC\\s*\\.\\s*put(?:Closeable)?|MdcScope\\s*\\.\\s*of)\\s*\\(\\s*\"([A-Za-z][\\w.\\-]*)\"");
    private static final Pattern ID_SUFFIX = Pattern.compile("(?i)[._\\-]?(id|uuid|key|number|no)$");
    private static final Set<String> AMBIENT = Set.of("traceId", "spanId", "trace.id", "span.id", "traceparent");
    private static final Set<String> SKIP_DIRS = Set.of("build", "out", "target", ".gradle", ".git", ".logast", "node_modules");

    public static boolean isGradleProject(Path root) {
        return Stream.of("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")
                .anyMatch(f -> Files.exists(root.resolve(f)));
    }

    /** MDC keys the code already sets, grouped by the business term they identify: {@code orderId → order}. */
    public static Map<String, SortedSet<String>> mdcKeysByTerm(Path root) {
        Map<String, SortedSet<String>> byTerm = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> root.relativize(p).toString().replace('\\', '/').contains("src/"))
                    .filter(p -> { for (Path seg : root.relativize(p)) { if (SKIP_DIRS.contains(seg.toString())) return false; } return true; })
                    .forEach(p -> {
                        Matcher m = MDC_KEY.matcher(read(p));
                        while (m.find()) {
                            String key = m.group(1);
                            if (!AMBIENT.contains(key)) {
                                byTerm.computeIfAbsent(term(key), t -> new TreeSet<>()).add(key);
                            }
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byTerm;
    }

    /** {@code orderId, order_id, order.id, orderUuid → order}; {@code job.name → job}; {@code tenant → tenant}. */
    static String term(String key) {
        if (key.contains(".")) {
            return key.substring(0, key.indexOf('.')).toLowerCase(Locale.ROOT);
        }
        String base = ID_SUFFIX.matcher(key).replaceFirst("");
        if (base.isEmpty()) {
            base = key;
        }
        String dotted = base.replaceAll("([a-z0-9])([A-Z])", "$1.$2").replaceAll("[_\\-]+", ".").toLowerCase(Locale.ROOT);
        return dotted;
    }

    public static String policyTemplate(List<String> extendsList, List<String> packs, boolean keepWording,
                                        Map<String, SortedSet<String>> mdcKeys) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                # logast policy for this service.
                # Layers: built-in defaults -> extends (company / area) -> this file. Lists append; `override: true` replaces.
                # `logast policy .` prints the merged result. Guide: docs/vocabulary.md in the logast distribution.

                """);
        if (extendsList.isEmpty()) {
            sb.append("# extends: [https://git.example.com/platform/observability/raw/main/company.yaml]\n\n");
        } else {
            sb.append("extends: [").append(String.join(", ", extendsList)).append("]\n\n");
        }
        sb.append("packs: [").append(String.join(", ", packs.isEmpty() ? List.of("logging", "tracing") : packs))
                .append("]   # logging, tracing, metrics\n\n");
        sb.append("logging:\n  rewriteExistingMessages: ").append(!keepWording)
                .append(keepWording ? "   # keep existing wording; still fix concatenation, glossary, lost exceptions\n"
                        : "    # set false to keep existing message wording\n");
        sb.append("""

                # Business vocabulary: one word per concept, one MDC key per identifier. Banned words in `aliases`
                # are rewritten to the term; old MDC key names in `mdc.aliases` are migrated to `mdc.key`.
                """);
        List<Map.Entry<String, SortedSet<String>>> suggestions = mdcKeys.entrySet().stream()
                .filter(e -> !e.getKey().isBlank()).toList();
        if (suggestions.isEmpty()) {
            sb.append("""
                    # vocabulary:
                    #   order:
                    #     aliases: [purchase]
                    #     mdc: { key: order.id, aliases: [orderId], sources: { params: [orderId] } }
                    """);
        } else {
            sb.append("# Suggested from the MDC keys this service already sets; uncomment and adjust:\n# vocabulary:\n");
            for (var e : suggestions) {
                String term = e.getKey();
                boolean idLike = e.getValue().stream().anyMatch(k -> ID_SUFFIX.matcher(k).find());
                String key = e.getValue().stream().filter(k -> k.startsWith(term + ".")).findFirst()
                        .orElse(idLike ? term + ".id" : term);
                List<String> aliases = e.getValue().stream().filter(k -> !k.equals(key)).toList();
                List<String> params = e.getValue().stream().filter(k -> k.matches("[a-z][A-Za-z0-9]*")).toList();
                sb.append("#   ").append(yamlKey(term)).append(":\n#     mdc: { key: ").append(key);
                if (!aliases.isEmpty()) {
                    sb.append(", aliases: [").append(String.join(", ", aliases)).append("]");
                }
                if (!params.isEmpty()) {
                    sb.append(", sources: { params: [").append(String.join(", ", params)).append("] }");
                }
                sb.append(" }\n");
            }
        }
        sb.append("""

                # Areas: packages where words mean something else, or extra MDC keys are required.
                # areas:
                #   billing:
                #     packages: [com.example.billing]
                #     requiredMdc: [tenant.id]
                """);
        return sb.toString();
    }

    private static String yamlKey(String term) {
        return term.matches("[a-z][a-z0-9_]*") ? term : "\"" + term + "\"";
    }

    /** Appends the logast entries missing from {@code <root>/.gitignore}; returns the ones added. */
    public static List<String> updateGitignore(Path root) throws IOException {
        Path file = root.resolve(".gitignore");
        String text = Files.exists(file) ? Files.readString(file) : "";
        Set<String> present = new HashSet<>(text.lines().map(String::strip).toList());
        if (present.contains(".logast/") || present.contains(".logast")) {
            // a blanket ignore would hide the shared files: replace it
            text = text.lines().filter(l -> !l.strip().equals(".logast/") && !l.strip().equals(".logast"))
                    .reduce("", (a, b) -> a + b + "\n");
            present.remove(".logast/");
            present.remove(".logast");
        }
        List<String> missing = GITIGNORE.stream().filter(l -> !present.contains(l)).toList();
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder(text);
            if (!text.isEmpty() && !text.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append("\n# logast: machine-specific classpath and scan output; baseline and traffic are shared\n");
            missing.forEach(l -> sb.append(l).append('\n'));
            Files.writeString(file, sb.toString());
        }
        return missing;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return "";
        }
    }
}
