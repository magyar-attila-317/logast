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

    /** .logast/ is ignored except what the team shares: the baseline, the decisions a person made, the README (#108). */
    public static final List<String> GITIGNORE = List.of(".logast/*", "!.logast/baseline.json", "!.logast/decisions/",
            "!.logast/README.md");
    /** Entries of the earlier layout (#108): the files they kept in git live in decisions/ now. */
    private static final Set<String> GITIGNORE_OBSOLETE = Set.of("!.logast/traffic.yaml", "!.logast/retry.yaml",
            "# logast: machine-specific classpath and scan output; baseline and traffic are shared");

    /** Every pack: the default since #122 (one command adopts logast; a pack is switched off by leaving it out). */
    public static final List<String> ALL_PACKS = List.of("logging", "tracing", "metrics", "resilience", "errors", "audit");

    /**
     * What {@code init} chooses for a service, and {@code apply} on a service without a policy (#122).
     *
     * @param keyStyle    null → the style the code uses most, else camel
     * @param supportMode null → not written (vendor, unless an extended layer says dependency)
     */
    public record Choices(List<String> extendsList, List<String> packs, boolean keepWording, String keyStyle, String supportMode) {
        public Choices {
            extendsList = extendsList == null ? List.of() : List.copyOf(extendsList);
            packs = packs == null ? List.of() : List.copyOf(packs);
        }

        public static Choices defaults() {
            return new Choices(List.of(), List.of(), false, null, null);
        }
    }

    /**
     * What {@link #bootstrap} did.
     *
     * @param policyWritten false: a policy file was there and kept
     * @param styleDetected true: the key style is the one the code uses most (not given, not the default)
     * @param logFormat     the format the service already uses; null → logstash, the default
     * @param terms         vocabulary terms found in the code
     * @param gitignoreAdded the .gitignore entries added
     */
    public record Bootstrap(Path policyFile, boolean policyWritten, List<String> packs, String keyStyle, boolean styleDetected,
                            String logFormat, int terms, List<String> gitignoreAdded) {
        /** One line: what the policy file says it chose. */
        public String summary() {
            return "packs " + String.join(", ", packs) + ", key style " + keyStyle + (styleDetected ? " (what the code uses most)" : "")
                    + ", log format " + (logFormat != null ? logFormat + " (what the service already uses)" : "logstash (default)")
                    + ", " + terms + " business terms found in the code";
        }
    }

    /**
     * {@code init}'s setup, shared with {@code apply} (#122): logast.yaml (unless one is there and not {@code force}),
     * with the key style and log format the code already uses and the vocabulary harvested from it; the .gitignore
     * entries; .logast/README.md.
     */
    public static Bootstrap bootstrap(Path root, Choices c, boolean force) throws IOException {
        Path policyFile = dev.logast.policy.PolicyLoader.defaultFile(root);
        boolean write = force || !Files.exists(policyFile);
        String style = null;
        boolean detected = false;
        String logFormat = null;
        int terms = 0;
        if (write) {
            dev.logast.policy.KeyStyle found = c.keyStyle() == null ? VocabularyHarvest.detectStyle(root) : null;
            detected = found != null;
            style = c.keyStyle() != null ? dev.logast.policy.KeyStyle.parse(c.keyStyle()).toString()
                    : (found != null ? found : dev.logast.policy.KeyStyle.CAMEL).toString();
            String mode = c.supportMode() == null ? null
                    : new dev.logast.policy.Policy.Support(null, null, c.supportMode(), null, null).mode();
            logFormat = detectLogFormat(root);
            // first without vocabulary, so the harvest can leave out what the extended company layers already define
            Files.writeString(policyFile, policyTemplate(c.extendsList(), c.packs(), c.keepWording(), style, mode, logFormat, null));
            VocabularyHarvest.Result h = VocabularyHarvest.run(root, Logast.policy(root, null), 3);
            Files.writeString(policyFile, policyTemplate(c.extendsList(), c.packs(), c.keepWording(), style, mode, logFormat,
                    VocabularyHarvest.yaml(h)));
            terms = h.terms().size();
        }
        List<String> added = updateGitignore(root);
        Path readme = root.resolve(".logast/README.md");
        Files.createDirectories(readme.getParent());
        Files.writeString(readme, logastReadme());
        return new Bootstrap(policyFile, write, c.packs().isEmpty() ? ALL_PACKS : c.packs(), style, detected, logFormat, terms, added);
    }

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

    /**
     * The JSON log format a service already uses, from its build and logging config: logstash
     * (logstash-logback-encoder, LogstashEncoder), gelf (logback-gelf, GelfEncoder), ecs (co.elastic.logging); null if none.
     */
    public static String detectLogFormat(Path root) {
        StringBuilder all = new StringBuilder();
        try (var files = java.nio.file.Files.walk(root, 6)) {
            files.filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/.gradle/") && !p.toString().contains("/node_modules/"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("build.gradle") || n.equals("libs.versions.toml") || n.startsWith("logback") && n.endsWith(".xml")
                                || n.startsWith("application") && (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties"));
                    })
                    .forEach(p -> {
                        try {
                            all.append(dev.logast.parse.SourceText.read(p)).append('\n');
                        } catch (java.io.IOException | java.io.UncheckedIOException ignored) {
                            // unreadable file: not evidence either way
                        }
                    });
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return null;
        }
        String t = all.toString();
        java.util.regex.Matcher configured = java.util.regex.Pattern
                .compile("structured\\.format\\.console\\s*[=:]\\s*(ecs|logstash|gelf)|console:\\s*(ecs|logstash|gelf)\\b").matcher(t);
        if (configured.find()) {
            return configured.group(1) != null ? configured.group(1) : configured.group(2);
        }
        if (t.contains("logstash-logback-encoder") || t.contains("LogstashEncoder") || t.contains("logstash.logback")) {
            return "logstash";
        }
        if (t.contains("logback-gelf") || t.contains("GelfEncoder") || t.contains("GelfLayout")) {
            return "gelf";
        }
        if (t.contains("co.elastic.logging") || t.contains("EcsEncoder")) {
            return "ecs";
        }
        return null;
    }

    static final String GUIDE = "https://github.com/magyar-attila-317/logast/blob/main/docs/configuration.md";

    /** @param supportMode null → not written (vendor, or dependency where the company layer names a repository) */
    public static String policyTemplate(List<String> extendsList, List<String> packs, boolean keepWording, String keyStyle,
                                        String supportMode, String vocabulary) {
        return policyTemplate(extendsList, packs, keepWording, keyStyle, supportMode, null, vocabulary);
    }

    /** @param logFormat the JSON log format the service already uses (null → logstash, the default) */
    public static String policyTemplate(List<String> extendsList, List<String> packs, boolean keepWording, String keyStyle,
                                        String supportMode, String logFormat, String vocabulary) {
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
        sb.append("# Which checks run. Default: all of them. Leave one out to switch it off.\n");
        sb.append("packs: [").append(String.join(", ", packs.isEmpty() ? ALL_PACKS : packs)).append("]\n\n");
        sb.append("logging:\n");
        sb.append("  # How MDC keys and [key=value] keys are spelled: camel (orderId), dotted (order.id), pascal (OrderId),\n");
        sb.append("  # snake (order_id). Applies to the vocabulary below too.\n");
        sb.append("  keyStyle: ").append(keyStyle != null ? keyStyle : "camel").append('\n');
        sb.append("  # true: logast may reword existing messages. false: keep their words, fix only the structure.\n");
        sb.append("  rewriteExistingMessages: ").append(!keepWording).append('\n');
        sb.append("  # JSON log format (logging.structured.format.console): ecs, logstash or gelf, whichever your log\n");
        sb.append("  # backend reads. none: logast leaves the log format alone.\n");
        sb.append("  structuredFormat: ").append(logFormat != null ? logFormat + "   # what this service already uses" : "logstash").append('\n');
        sb.append("  # Messages that alerts or dashboards search for (regex): logast never changes them, it only reports.\n");
        sb.append("  # protectedMessages: [\"^ALERT:\"]\n\n");
        sb.append("""
                # Where generated code gets MdcScope / ObservedContext from:
                #   vendor      copies the classes into this service, no library needed (the default)
                #   dependency  adds the logging-support library (it must be in a repository your build uses; the
                #               default when an extended policy sets support.repository)
                """);
        sb.append(supportMode != null ? "support:\n  mode: " + supportMode + "\n\n" : "# support:\n#   mode: dependency\n\n");
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
                # The entries below were found in your code, one per key as the code spells it. Organise them as
                # the example shows, then run `logast vocabulary .` again: it lists only what is still missing.
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

                Files logast keeps for this service. `decisions/` holds what a person decides (you edit these, they go into
                git); `reports/` holds what logast writes for you to read (rewritten on every run, not in git).

                | file | what it is | in git? |
                |---|---|---|
                | `decisions/messages.yaml` | the message texts only a person can write (MSG), one entry per finding that needs one. `scan` and `apply` keep it up to date and keep what you wrote; `logast review . --open` edits it in a browser. | yes |
                | `decisions/metrics.yaml` | per logged outcome: count it (`counter`, `tag`) or `skip` it (metrics pack). Kept up to date like messages.yaml. | yes |
                | `decisions/traffic.yaml` | every entrypoint of the service, with calls per day if you fill them in. Used to judge log volume (LOG_VOLUME). Leave a value empty if you don't know it. Refresh the list with `logast traffic .`. | yes |
                | `decisions/retry.yaml` | every `@Retryable` usage (resilience pack). By default they share one block of retry settings in `application.yml`; `specific: true` gives a usage its own block on the next `apply`. | yes |
                | `baseline.json` | the findings that existed when it was recorded (`logast scan . --update-baseline`). CI uses it to fail only on *new* findings: `logast scan . --baseline --fail-on high`. | yes |
                | `classpath-*.txt` | the service's compile classpath, written by `logast classpath .` (Gradle runs once). logast needs it to understand types. Run it again after you change dependencies. | no: paths are machine-specific |
                | `reports/orders.json` | the result of the last `logast scan` (full detail, read by `apply` and `review`). | no |
                | `reports/coverage.txt` | every log call and what logast did with it: fixed, reported, ignored, skipped (with the reason) or checked. Written by `scan` and `apply --write` with the logging pack. | no |
                | `reports/steps/`, `reports/apply-trace.txt`, `reports/last-apply.txt` | the last `apply`: one diff per kind of change, what each round did, what it changed. | no |
                | `reports/queries.md` | dashboard and alert queries from `logast queries .`. | no |
                | `reports/verify.json` | the result of the last `logast verify`. | no |

                Configuration lives in `logast.yaml` next to this folder: %s
                """.formatted(GUIDE);
    }

    private static List<String> missingFrom(Set<String> present) {
        return GITIGNORE.stream().filter(l -> !present.contains(l)).toList();
    }

    /** Appends the logast entries missing from {@code <root>/.gitignore} (dropping the earlier layout's); returns the ones added. */
    public static List<String> updateGitignore(Path root) throws IOException {
        Path file = root.resolve(".gitignore");
        String text = Files.exists(file) ? dev.logast.parse.SourceText.read(file) : "";
        Set<String> present = new HashSet<>(text.lines().map(String::strip).toList());
        if (present.contains(".logast/") || present.contains(".logast")) {
            // a blanket ignore would hide the shared files: replace it
            text = text.lines().filter(l -> !l.strip().equals(".logast/") && !l.strip().equals(".logast"))
                    .reduce("", (a, b) -> a + b + "\n");
            present.remove(".logast/");
            present.remove(".logast");
        }
        if (present.stream().anyMatch(GITIGNORE_OBSOLETE::contains)) {
            text = text.lines().filter(l -> !GITIGNORE_OBSOLETE.contains(l.strip())).reduce("", (a, b) -> a + b + "\n");
            present.removeAll(GITIGNORE_OBSOLETE);
            if (missingFrom(present).isEmpty()) {
                dev.logast.parse.SourceText.write(file, text);
            }
        }
        List<String> missing = missingFrom(present);
        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder(text);
            if (!text.isEmpty() && !text.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append("\n# logast: machine-specific classpath and reports; baseline and decisions are shared\n");
            missing.forEach(l -> sb.append(l).append('\n'));
            dev.logast.parse.SourceText.write(file, sb.toString());
        }
        return missing;
    }

}
