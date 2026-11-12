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

    public static boolean isGradleProject(Path root) {
        return Stream.of("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")
                .anyMatch(f -> Files.exists(root.resolve(f)));
    }

    /** @param vocabulary harvested {@code vocabulary:} block ({@link VocabularyHarvest#yaml}); empty → commented example */
    public static String policyTemplate(List<String> extendsList, List<String> packs, boolean keepWording, String vocabulary) {
        return policyTemplate(extendsList, packs, keepWording, null, vocabulary);
    }

    /** @param keyStyle null → not written (default or company layer decides) */
    public static String policyTemplate(List<String> extendsList, List<String> packs, boolean keepWording, String keyStyle,
                                        String vocabulary) {
        return policyTemplate(extendsList, packs, keepWording, keyStyle, null, vocabulary);
    }

    /** @param supportMode null → not written (dependency, or what the company layer says); "vendor" → copy the classes */
    public static String policyTemplate(List<String> extendsList, List<String> packs, boolean keepWording, String keyStyle,
                                        String supportMode, String vocabulary) {
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
        sb.append(keyStyle != null ? "  keyStyle: " + keyStyle + "   # MDC and [k=v] keys: dotted (order.id), camel (orderId), pascal (OrderId), snake (order_id)\n"
                : "  # keyStyle: camel             # MDC and [k=v] keys: dotted (order.id, default), camel (orderId), pascal (OrderId), snake (order_id)\n");
        sb.append("\n# Generated code uses MdcScope / ObservedContext from the logging-support starter:\n")
                .append("#   dependency: add dev.logast:logging-support (must be resolvable: Nexus, or mavenLocal after `install.sh --starter`)\n")
                .append("#   vendor:     copy the classes into <@SpringBootApplication package>.logging (no library)\n")
                .append(supportMode != null ? "support:\n  mode: " + supportMode + "\n" : "# support:\n#   mode: vendor\n");
        sb.append("""

                # Business vocabulary: one word per concept, one MDC key per identifier. Banned words in `aliases`
                # are rewritten to the term; old MDC key names in `mdc.aliases` are migrated to `mdc.key`.
                """);
        if (vocabulary == null || !vocabulary.contains("vocabulary:")) {
            sb.append("""
                    # vocabulary:
                    #   order:
                    #     aliases: [purchase]
                    #     mdc: { key: order.id, aliases: [orderId], sources: { params: [orderId] } }
                    """);
        } else {
            sb.append(vocabulary);
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

}
