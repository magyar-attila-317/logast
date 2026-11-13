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

    public static final List<String> GITIGNORE = List.of(".logast/*", "!.logast/baseline.json", "!.logast/traffic.yaml",
            "!.logast/README.md");

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

    static final String GUIDE = "https://github.com/magyar-attila-317/logast/blob/main/docs/configuration.md";

    /** @param supportMode null → not written (dependency, or what the company layer says); "vendor" → copy the classes */
    public static String policyTemplate(List<String> extendsList, List<String> packs, boolean keepWording, String keyStyle,
                                        String supportMode, String vocabulary) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                # =============================================================================================
                # logast.yaml: how this service should log, trace and measure
                #
                # logast reads this file on every run. Anything you leave out uses the built-in default.
                # Lines starting with "#" are switched-off examples: remove the "#" to use one.
                #
                # Handy commands
                #   logast policy .                 the final settings (this file + extends + defaults)
                #   logast vocabulary .             business ids in the code that are not listed below yet
                #   logast scan . --format summary  what logast would change
                #
                # Every option, explained: %s
                # =============================================================================================

                """.formatted(GUIDE));
        sb.append("# Shared settings for all your services (a URL or a file). Lists add up; this file wins on conflicts.\n");
        if (extendsList.isEmpty()) {
            sb.append("# extends: [https://git.example.com/platform/observability/raw/main/company.yaml]\n\n");
        } else {
            sb.append("extends: [").append(String.join(", ", extendsList)).append("]\n\n");
        }
        sb.append("# Which checks run. Default: logging, tracing. Add any of: metrics, resilience, errors, audit.\n");
        sb.append("packs: [").append(String.join(", ", packs.isEmpty() ? List.of("logging", "tracing") : packs)).append("]\n\n");
        sb.append("logging:\n");
        sb.append("  # How MDC keys and [key=value] keys are spelled: camel (orderId), dotted (order.id), pascal (OrderId),\n");
        sb.append("  # snake (order_id). Applies to the vocabulary below too.\n");
        sb.append("  keyStyle: ").append(keyStyle != null ? keyStyle : "camel").append('\n');
        sb.append("  # true: logast may reword existing messages. false: keep their words, fix only the structure.\n");
        sb.append("  rewriteExistingMessages: ").append(!keepWording).append('\n');
        sb.append("  # Messages that alerts or dashboards search for (regex): logast never changes them, it only reports.\n");
        sb.append("  # protectedMessages: [\"^ALERT:\"]\n\n");
        sb.append("""
                # Where generated code gets MdcScope / ObservedContext from:
                #   dependency  adds the logging-support library (it must be in a repository your build uses)
                #   vendor      copies the classes into this service, no library needed
                """);
        sb.append(supportMode != null ? "support:\n  mode: " + supportMode + "\n\n" : "# support:\n#   mode: vendor\n\n");
        sb.append("""
                # ---------------------------------------------------------------------------------------------
                # Business vocabulary: one entry per business concept
                #
                # logast puts each id into MDC where a flow starts (endpoint, listener, job) and removes it from
                # the messages below that point, because every log line already carries it.
                #   aliases       other words for the same thing; rewritten to the term in messages
                #   mdc.key       the MDC key for the id; mdc.aliases are old key names, renamed to mdc.key
                #   mdc.sources   where the id comes from: parameter names, or "com.x.Type: accessor()"
                #
                # The entries below were found in your code. Rename terms, merge duplicates, and delete what is not
                # a business concept. Then run `logast vocabulary .` again: it lists only what is still missing.
                # ---------------------------------------------------------------------------------------------
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

                # ---------------------------------------------------------------------------------------------
                # More options (remove the "#" to use one)
                # ---------------------------------------------------------------------------------------------

                # Areas: packages where a word means something else, or where extra MDC keys are required.
                # areas:
                #   billing:
                #     packages: [com.example.billing]
                #     requiredMdc: [tenantId]

                # Personal data: values with these names never reach a log line or a metric tag (adds to the built-ins).
                # pii: [guestName, guestPhone]

                # Exceptions caused by the caller (logged at WARN at most, never ERROR). Adds to the built-in list.
                # levels:
                #   clientErrors: [com.example.BusinessRuleViolationException]
                #   infoInLoops: size            # INFO per loop item: fine when small, DEBUG over database rows/streams,
                #                                # reported when unknown (`// logast:loop-size 10` above a loop settles it)

                # Your own annotations that start work, like @KafkaListener does.
                # entrypoints:
                #   extraAnnotations: [com.example.InboxHandler]

                # Gradle tasks that run the tests, for `logast verify` (default: test).
                # verify:
                #   tasks: [test, intTest]

                # Turn single rules off or change how serious they are (`logast rules` lists all of them).
                # rules:
                #   ENTRYPOINT_SILENT: { severity: low }
                #   LOG_IN_LOOP: { enabled: false }

                # The metrics, resilience and audit packs have their own settings: see the guide linked at the top.
                """);
        return sb.toString();
    }

    /** {@code .logast/README.md}: what each file in the folder is for, and which ones belong in git. */
    public static String logastReadme() {
        return """
                # .logast

                Files logast writes for this service. You rarely edit them by hand.

                | file | what it is | in git? |
                |---|---|---|
                | `classpath-*.txt` | the service's compile classpath, written by `logast classpath .` (Gradle runs once). logast needs it to understand types. Run it again after you change dependencies. | no: paths are machine-specific |
                | `traffic.yaml` | every entrypoint of the service, with calls per day if you fill them in. Used to judge log volume (LOG_VOLUME). Leave a value empty if you don't know it. Refresh the list with `logast traffic .`. | yes |
                | `baseline.json` | the findings that existed when it was recorded (`logast scan . --update-baseline`). CI uses it to fail only on *new* findings: `logast scan . --baseline --fail-on high`. | yes |
                | `orders.json` | the result of the last `logast scan` (full detail, read by `apply` and `review`). | no |
                | `verify.json` | the result of the last `logast verify`. | no |

                Configuration lives in `logast.yaml` next to this folder: %s
                """.formatted(GUIDE);
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
