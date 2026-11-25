package dev.logast;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
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
        boolean write = force || needsSetup(root);
        String style = null;
        boolean detected = false;
        String logFormat = null;
        int terms = 0;
        if (write) {
            // #124: a file with only `fleet:` / `extends:` (what `logast fleet join` writes) keeps those lines
            com.fasterxml.jackson.databind.JsonNode kept = Files.exists(policyFile) ? readYaml(policyFile) : null;
            String fleet = kept == null ? "" : kept.path("fleet").asText("").strip();
            List<String> extendsList = new ArrayList<>(c.extendsList());
            if (extendsList.isEmpty() && kept != null) {
                com.fasterxml.jackson.databind.JsonNode ext = kept.path("extends");
                if (ext.isTextual()) {
                    extendsList.add(ext.asText());
                } else {
                    ext.forEach(e -> extendsList.add(e.asText()));
                }
            }
            // the parents decide first: a setting the fleet's company.yaml or an extended layer sets stays theirs
            Files.writeString(policyFile, (fleet.isEmpty() ? "" : "fleet: " + fleet + "\n")
                    + extendsYaml(extendsList));
            Set<String> parents = dev.logast.policy.PolicyLoader.parentSettings(policyFile);
            dev.logast.policy.Policy inherited = Logast.policy(root, null);
            dev.logast.policy.KeyStyle found = c.keyStyle() == null ? VocabularyHarvest.detectStyle(root) : null;
            detected = found != null;
            style = c.keyStyle() != null ? dev.logast.policy.KeyStyle.parse(c.keyStyle()).toString()
                    : (found != null ? found : dev.logast.policy.KeyStyle.CAMEL).toString();
            String mode = c.supportMode() == null ? null
                    : new dev.logast.policy.Policy.Support(null, null, c.supportMode(), null, null).mode();
            logFormat = detectLogFormat(root);
            // first without vocabulary, so the harvest can leave out what the extended company layers already define
            Files.writeString(policyFile, ownLayer(policyTemplate(extendsList, c.packs(), c.keepWording(), style, mode, logFormat, null),
                    fleet, parents));
            VocabularyHarvest.Result h = VocabularyHarvest.run(root, Logast.policy(root, null), 3);
            String own = suggestClientErrors(ownLayer(policyTemplate(extendsList, c.packs(), c.keepWording(), style,
                    mode, logFormat, VocabularyHarvest.yaml(h)), fleet, parents), root);
            // #290: how the code logs a caught exception, unless a parent layer decides it
            boolean parentDecides = dev.logast.policy.PolicyLoader.sets(parents, "logging.exceptionStack")
                    || dev.logast.policy.PolicyLoader.sets(parents, "logging.exceptionMessage");
            Files.writeString(policyFile, parentDecides ? own : suggestExceptionPolicy(own, root));
            terms = h.terms().size();
            if (dev.logast.policy.PolicyLoader.sets(parents, "logging.keyStyle") && c.keyStyle() == null) {
                style = inherited.logging().keyStyle() + " (from " + (fleet.isEmpty() ? "an extended layer" : "fleet " + fleet) + ")";
                detected = false;
            }
        }
        List<String> added = updateGitignore(root);
        Path readme = root.resolve(".logast/README.md");
        Files.createDirectories(readme.getParent());
        Files.writeString(readme, logastReadme());
        List<String> packs = c.packs().isEmpty() ? ALL_PACKS : c.packs();
        if (write) {   // a fleet or extended layer may choose them (#124)
            packs = Logast.policy(root, null).packs();
        }
        return new Bootstrap(policyFile, write, packs, style, detected, logFormat, terms, added);
    }

    /**
     * The {@code extends:} lines: a flow list, or with a fleet reference (#133) a block list whose fleet entries say
     * where they resolve on this machine.
     */
    static String extendsYaml(List<String> extendsList) {
        if (extendsList.isEmpty()) {
            return "";
        }
        if (extendsList.stream().noneMatch(e -> dev.logast.fleet.FleetExtends.name(e) != null)) {
            return "extends: [" + String.join(", ", extendsList) + "]\n";
        }
        StringBuilder sb = new StringBuilder("extends:\n");
        for (String e : extendsList) {
            String fleet = dev.logast.fleet.FleetExtends.name(e);
            String pin = dev.logast.fleet.FleetExtends.pin(e);   // #236: a pinned policy version stays pinned
            sb.append(fleet == null ? "  - " + e : pin != null ? "  - " + e.strip() + "    # = "
                    + dev.logast.fleet.FleetExtends.company(fleet, pin) + " on this machine"
                    : dev.logast.fleet.FleetExtends.item("  ", fleet, dev.logast.fleet.FleetExtends.company(fleet))).append('\n');
        }
        return sb.toString();
    }

    /**
     * True when the service has no policy of its own yet: no logast.yaml, or one that holds only {@code fleet:} and
     * {@code extends:} (and comments), as {@code logast fleet join} leaves it (#124).
     */
    public static boolean needsSetup(Path root) {
        Path file = dev.logast.policy.PolicyLoader.defaultFile(root);
        if (!Files.exists(file)) {
            return true;
        }
        com.fasterxml.jackson.databind.JsonNode n = readYaml(file);
        if (n == null || n.isMissingNode() || n.isNull()) {
            return true;
        }
        if (!n.isObject()) {
            return false;
        }
        Iterator<String> names = n.fieldNames();
        while (names.hasNext()) {
            if (!Set.of("fleet", "extends").contains(names.next())) {
                return false;
            }
        }
        return true;
    }

    private static com.fasterxml.jackson.databind.JsonNode readYaml(Path file) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                    .readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** The template's lines a parent layer sets ({@link dev.logast.policy.PolicyLoader#parentSettings}), by YAML path. */
    private static final Map<String, String> OWN_LINES = Map.of("packs: ", "packs", "  keyStyle: ", "logging.keyStyle",
            "  rewriteExistingMessages: ", "logging.rewriteExistingMessages", "  structuredFormat: ", "logging.structuredFormat",
            "  mode: ", "support.mode");

    /**
     * #124: the starter as the service's own layer under its parents. The fleet line goes on top; a setting the fleet's
     * company.yaml or an extended layer sets is left to it: its line stays in the file, commented out, so the service
     * neither repeats nor silently overrides the shared decision (an object left empty is commented out with it).
     */
    static String ownLayer(String template, String fleet, Set<String> parents) {
        String by = fleet.isEmpty() ? "an extended layer" : "fleet " + fleet + "'s company.yaml";
        List<String> out = new ArrayList<>();
        int objectAt = -1;
        boolean anyKept = false;
        for (String line : template.split("\n", -1)) {
            if (!fleet.isEmpty() && line.startsWith("# Shared settings for all your services")) {
                out.add("# The fleet this service belongs to (`logast fleet join`): its company.yaml comes first, this file wins.");
                out.add("fleet: " + fleet);
                out.add("");
            }
            if (line.equals("logging:") || line.equals("support:")) {
                objectAt = out.size();
                anyKept = false;
                out.add(line);
                continue;
            }
            String path = OWN_LINES.entrySet().stream().filter(e -> line.startsWith(e.getKey())).map(Map.Entry::getValue)
                    .findFirst().orElse(null);
            if (path != null && dev.logast.policy.PolicyLoader.sets(parents, path)) {
                out.add(line.replaceFirst("^( *)", "$1# ") + "   # decided by " + by);
                continue;
            }
            if (objectAt >= 0 && line.startsWith("  ") && !line.stripLeading().startsWith("#")) {
                anyKept = true;
            }
            if (objectAt >= 0 && !line.startsWith(" ")) {
                if (!anyKept) {
                    out.set(objectAt, "# " + out.get(objectAt));
                }
                objectAt = -1;
            }
            out.add(line);
        }
        return String.join("\n", out);
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

    private static final String CLIENT_CODES = "(BAD_REQUEST|UNAUTHORIZED|PAYMENT_REQUIRED|FORBIDDEN|NOT_FOUND|"
            + "METHOD_NOT_ALLOWED|NOT_ACCEPTABLE|CONFLICT|GONE|PRECONDITION_FAILED|PAYLOAD_TOO_LARGE|UNSUPPORTED_MEDIA_TYPE|"
            + "UNPROCESSABLE_ENTITY|UNPROCESSABLE_CONTENT|LOCKED|PRECONDITION_REQUIRED|TOO_MANY_REQUESTS)";
    private static final java.util.regex.Pattern CLIENT_STATUS = java.util.regex.Pattern.compile(
            "@ResponseStatus\\s*\\((?:[^)]*?)HttpStatus\\." + CLIENT_CODES + "[^)]*\\)"
                    + "(?:\\s|@\\w+(?:\\([^)]*\\))?)*public\\s+(?:final\\s+)?class\\s+(\\w+)\\s+extends\\s+\\w*(?:Exception|Error)\\b");
    /** An {@code @ExceptionHandler} and the exception classes it names. */
    private static final java.util.regex.Pattern HANDLER = java.util.regex.Pattern.compile(
            "@ExceptionHandler\\s*\\(\\s*(?:value\\s*=\\s*)?(\\{[^}]*}|[\\w.]+\\.class)");
    /** A handler's answer is a 4xx: its @ResponseStatus, a ResponseEntity / ProblemDetail with a 4xx status. */
    private static final java.util.regex.Pattern CLIENT_ANSWER = java.util.regex.Pattern.compile(
            "HttpStatus\\." + CLIENT_CODES + "\\b|ResponseEntity\\s*\\.\\s*(badRequest|notFound|unprocessableEntity)\\s*\\("
                    + "|(status|valueOf)\\s*\\(\\s*4\\d\\d\\s*\\)");

    /** A catch clause and the name of the caught exception. */
    private static final java.util.regex.Pattern CATCH = java.util.regex.Pattern.compile("catch\\s*\\(\\s*(?:final\\s+)?[\\w.|\\s]+?\\s(\\w+)\\s*\\)\\s*\\{");
    private static final java.util.regex.Pattern LOG_CALL = java.util.regex.Pattern.compile(
            "\\b(?:log|logger|LOG|LOGGER)\\s*\\.\\s*(?:error|warn|info|debug)\\s*\\(");
    /** At least this many catch-block log lines before the code's habit is written as the policy. */
    static final int EXCEPTION_SAMPLES = 5;

    /**
     * #290: how the code logs a caught exception, from its catch blocks: when (of at least {@link #EXCEPTION_SAMPLES}
     * log lines) at most a fifth pass the exception to the logger, {@code exceptionStack: never}; when at most a tenth log
     * its message and at least half its class, {@code exceptionMessage: never}. Written with what was counted, for the
     * owner to confirm; otherwise the two settings are offered commented.
     */
    static String suggestExceptionPolicy(String policy, Path root) {
        String anchor = "  # protectedMessages: [\"^ALERT:\"]\n";
        if (!policy.contains(anchor)) {
            return policy;
        }
        int lines = 0;
        int stack = 0;
        int message = 0;
        int classOnly = 0;
        Path src = root.resolve("src/main/java");
        if (Files.isDirectory(src)) {
            try (var files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String text = Files.readString(f);
                    java.util.regex.Matcher c = CATCH.matcher(text);
                    while (c.find()) {
                        int close = closingBrace(text, c.end() - 1);
                        if (close < 0) {
                            continue;
                        }
                        String var = java.util.regex.Pattern.quote(c.group(1));
                        String block = text.substring(c.end(), close);
                        java.util.regex.Matcher l = LOG_CALL.matcher(block);
                        while (l.find()) {
                            int end = block.indexOf(");", l.end());
                            if (end < 0) {
                                continue;
                            }
                            String call = block.substring(l.end(), end + 1);
                            lines++;
                            if (call.matches("(?s).*,\\s*" + var + "\\s*\\)$")) {
                                stack++;
                            }
                            if (call.matches("(?s).*\\b" + var + "\\s*\\.\\s*get(Localized)?Message\\s*\\(.*")) {
                                message++;
                            } else if (call.matches("(?s).*\\b" + var + "\\s*\\.\\s*getClass\\s*\\(.*")) {
                                classOnly++;
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                return policy;
            }
        }
        String header = "  # How a caught exception is logged: exceptionStack attach (the default) passes it to the logger with\n"
                + "  # its stack trace, never logs its class only; exceptionMessage keep (the default) or never (its class\n"
                + "  # instead: a message may hold personal data).\n";
        boolean noStack = lines >= EXCEPTION_SAMPLES && stack * 5 <= lines;
        boolean noMessage = lines >= EXCEPTION_SAMPLES && message * 10 <= lines && classOnly * 2 >= lines;
        String counted = " of " + lines + " log lines in catch blocks";
        String stackLine = noStack ? "  exceptionStack: never     # found: " + (lines - stack) + counted + " pass no exception\n"
                : "  # exceptionStack: never\n";
        String messageLine = noMessage ? "  exceptionMessage: never   # found: " + classOnly + counted + " log its class, " + message + " its message\n"
                : "  # exceptionMessage: never\n";
        return policy.replace(anchor, anchor + header + stackLine + messageLine);
    }

    /**
     * #279: the service's own exceptions that answer with a 4xx status ({@code @ResponseStatus(HttpStatus.CONFLICT)} on the
     * class): caller errors, offered in the commented levels.clientErrors example for the owner to confirm.
     */
    static String suggestClientErrors(String policy, Path root) {
        List<String> found = new ArrayList<>();
        Path src = root.resolve("src/main/java");
        if (Files.isDirectory(src)) {
            try (var files = Files.walk(src)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    String text = Files.readString(f);
                    java.util.regex.Matcher m = CLIENT_STATUS.matcher(text);
                    java.util.regex.Matcher pkg = java.util.regex.Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;").matcher(text);
                    String prefix = pkg.find() ? pkg.group(1) + "." : "";
                    if (m.find()) {
                        found.add(prefix + m.group(2));
                    }
                    for (String handled : handledAsClientErrors(text, prefix)) {
                        if (!found.contains(handled)) {
                            found.add(handled);
                        }
                    }
                }
            } catch (IOException e) {
                return policy;
            }
        }
        String example = "#   clientErrors: [com.example.BusinessRuleViolationException]";
        if (found.isEmpty() || !policy.contains(example)) {
            return policy;
        }
        return policy.replace(example, "#   clientErrors: [" + String.join(", ", found) + "]   # found: @ResponseStatus 4xx, 4xx handlers in the code");
    }

    /**
     * #279: the exceptions an {@code @ExceptionHandler} answers with a 4xx (its {@code @ResponseStatus}, or a
     * ResponseEntity / ProblemDetail with a 4xx status in its body), named as the file imports them (else in its package).
     */
    static List<String> handledAsClientErrors(String text, String prefix) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher h = HANDLER.matcher(text);
        while (h.find()) {
            int open = text.indexOf('{', h.end());
            int close = open < 0 ? -1 : closingBrace(text, open);
            if (close < 0) {
                continue;
            }
            // the method's annotations, signature and body (the next handler's annotations are not part of it)
            String method = text.substring(h.start(), close + 1);
            if (!CLIENT_ANSWER.matcher(method).find()) {
                continue;
            }
            java.util.regex.Matcher c = java.util.regex.Pattern.compile("([\\w.]+)\\.class").matcher(h.group(1));
            while (c.find()) {
                String name = c.group(1);
                if (!name.contains(".")) {
                    java.util.regex.Matcher imp = java.util.regex.Pattern.compile("(?m)^import\\s+([\\w.]+\\." + name + ")\\s*;").matcher(text);
                    name = imp.find() ? imp.group(1) : javaLang(name) ? "java.lang." + name : prefix + name;
                }
                if (!out.contains(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    private static boolean javaLang(String simpleName) {
        try {
            Class.forName("java.lang." + simpleName, false, ServiceSetup.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static int closingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The JSON log format a service already uses, from its build and logging config: logstash
     * (logstash-logback-encoder, LogstashEncoder), gelf (logback-gelf, GelfEncoder), ecs (co.elastic.logging); null if none.
     */
    private static final java.util.regex.Pattern JSON_ENCODER = java.util.regex.Pattern.compile(
            "class=\"[\\w.]*(LogstashEncoder|LoggingEventCompositeJsonEncoder|EcsEncoder|GelfEncoder|JsonEncoder|LogstashLayout|JsonLayout)\"");

    /** #296: the text without comments: YAML and properties {@code #…}, XML {@code <!-- … -->}, Gradle {@code //…}. */
    public static String uncommented(String name, String text) {
        if (name.endsWith(".xml")) {
            return text.replaceAll("(?s)<!--.*?-->", "");
        }
        if (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties") || name.endsWith(".toml")) {
            return text.replaceAll("(?m)^\\s*[#!].*$", "").replaceAll("(?m)\\s+#.*$", "");
        }
        return text.replaceAll("(?m)//.*$", "");
    }

    public static String detectLogFormat(Path root) {
        StringBuilder all = new StringBuilder();
        StringBuilder ownEncoder = new StringBuilder();
        try (var files = java.nio.file.Files.walk(root, 6)) {
            files.filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/.gradle/") && !p.toString().contains("/node_modules/"))
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("build.gradle") || n.equals("libs.versions.toml") || n.startsWith("logback") && n.endsWith(".xml")
                                || n.startsWith("application") && (n.endsWith(".yml") || n.endsWith(".yaml") || n.endsWith(".properties"));
                    })
                    .forEach(p -> {
                        try {
                            String n = p.getFileName().toString();
                            String text = uncommented(n, dev.logast.parse.SourceText.read(p));
                            // #296: a logback file with a JSON encoder of its own owns the format; Boot's structured
                            // logging property does nothing next to it
                            if (n.startsWith("logback") && JSON_ENCODER.matcher(text).find()) {
                                ownEncoder.append(n);
                            }
                            all.append(text).append('\n');
                        } catch (java.io.IOException | java.io.UncheckedIOException ignored) {
                            // unreadable file: not evidence either way
                        }
                    });
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return null;
        }
        if (!ownEncoder.isEmpty()) {
            return "none";
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
                #   logast explain policy .         the final settings (this file + extends + defaults)
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
            sb.append(extendsYaml(extendsList)).append('\n');
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
        sb.append("  structuredFormat: ").append(logFormat == null ? "logstash" : logFormat.equals("none")
                ? "none   # the service's logback file configures its own JSON encoder" : logFormat + "   # what this service already uses").append('\n');
        sb.append("  # Messages that alerts or dashboards search for (regex): logast never changes them, it only reports.\n");
        sb.append("  # protectedMessages: [\"^ALERT:\"]\n\n");
        sb.append("""
                # Callers' trace context (traceparent, baggage): true continues their traces (services behind one
                # gateway share one trace); false for a service the internet reaches directly, where a caller could
                # pick the trace id it logs under. Written out so a changed default never changes this service.
                entrypoints:
                  trustInboundTrace: true

                # Share of traces exported (management.tracing.sampling.probability). Unset: only proposed.
                # tracing:
                #   samplingProbability: 1.0

                """);
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

                # Your own annotations that start work, like @KafkaListener does (under entrypoints: above).
                #   extraAnnotations: [com.example.InboxHandler]

                # Gradle tasks that run the tests, for `logast verify` (default: test).
                # verify:
                #   tasks: [test, intTest]

                # Turn single rules off or change how serious they are (`logast explain rules` lists all of them).
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
                | `reports/metrics.md`, `reports/queries.md`, `reports/lifecycle.txt` | what the service measures (and what it does not), dashboard and alert queries (PromQL, NRQL, Splunk), every flow's start and outcome lines. Written by every `scan` and `apply`; `logast explain metrics` (`queries`, `lifecycle`) prints them. | no |
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
