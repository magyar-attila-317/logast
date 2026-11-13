package dev.logast.rules;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import org.openrewrite.Cursor;
import org.openrewrite.yaml.MergeYamlVisitor;
import org.openrewrite.yaml.YamlParser;
import org.openrewrite.yaml.tree.Yaml;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Project-level checks: build dependencies, logback configuration, structured logging properties. */
public final class SetupRules {
    private static final Pattern DEPENDENCIES = Pattern.compile("(?m)^dependencies\\s*\\{[^\\n]*$");
    private static final Set<String> STANDARD_APPENDERS = Set.of("ch.qos.logback.core.ConsoleAppender");
    private static final Set<String> STANDARD_ENCODERS = Set.of("", "ch.qos.logback.classic.encoder.PatternLayoutEncoder");
    private static final Set<String> JSON_FORMATS = Set.of("ecs", "logstash", "gelf");

    private final RuleContext ctx;
    private final Path root;

    SetupRules(RuleContext ctx) {
        this.ctx = ctx;
        this.root = ctx.a.project.root();
    }

    void run() {
        Optional<ClassInfo> app = ctx.a.index.classes().stream()
                .filter(c -> c.decl().getLeadingAnnotations().stream()
                        .anyMatch(an -> Annotations.is(an, "org.springframework.boot.autoconfigure.SpringBootApplication")))
                .findFirst();
        Path start = app.map(c -> c.file().absPath().getParent()).orElse(root);
        Path buildFile = findBuildFile(start);
        if (buildFile != null) {
            dependencies(buildFile);
        }
        Path resources = app.map(c -> resourcesDir(c.file().absPath())).orElse(root.resolve("src/main/resources"));
        logback(resources);
        structuredLogging(resources);
        if (app.isPresent()) {
            serviceIdentity();
        }
    }

    // ---- dependencies ------------------------------------------------------------------------

    private void dependencies(Path buildFile) {
        String text = read(buildFile);
        boolean kts = buildFile.toString().endsWith(".kts");
        String rel = rel(buildFile);

        String support = ctx.policy.support().coordinates();
        if (ctx.policy.support().vendor()) {
            if (!ctx.a.supportStarterPresent && ctx.enabled(Rules.SETUP_SUPPORT_STARTER)) {
                vendor(rel, text, kts);
            } else if (ctx.a.supportStarterPresent && ctx.enabled(Rules.SETUP_SUPPORT_STARTER)) {
                updateVendored();
            }
        } else if (!ctx.a.supportStarterPresent && !text.contains(ctx.policy.support().groupAndArtifact())
                && ctx.enabled(Rules.SETUP_SUPPORT_STARTER)) {
            OrderBuilder b = ctx.order(Rules.SETUP_SUPPORT_STARTER, "support|" + rel).at(rel, 1)
                    .title("Add the logging-support starter (" + support + ")")
                    .suggestion("Provides ObservedContext, MdcScope, ContextExecutors, MDC/trace propagation for @Async "
                            + "and Spring executors, batch trace bootstrap (TRACEPARENT / TRACE_ID) and ECS JSON log defaults.");
            Edit e = dependencyInsert(rel, text, kts, support);
            if (e == null) {
                b.suggestion(b.suggestion + " No top-level dependencies {} block found: add it manually.");
            } else {
                b.edit(e).line = lineOf(text, e.start());
            }
            asyncLocations(b);
            ctx.supportOrderId = b.id();
        }

        boolean otelOnClasspath = ctx.a.project.classpath().stream()
                .anyMatch(p -> p.getFileName().toString().startsWith("spring-boot-starter-opentelemetry"));
        if (!otelOnClasspath && !text.contains("spring-boot-starter-opentelemetry")
                && ctx.enabled(Rules.SETUP_OTEL_STARTER)) {
            OrderBuilder b = ctx.order(Rules.SETUP_OTEL_STARTER, "otel|" + rel).at(rel, 1)
                    .title("Add spring-boot-starter-opentelemetry (tracing + trace/span ids in logs)");
            Edit e = dependencyInsert(rel, text, kts, "org.springframework.boot:spring-boot-starter-opentelemetry");
            if (e == null) {
                b.suggestion("No top-level dependencies {} block found: add it manually.");
            } else {
                b.edit(e).line = lineOf(text, e.start());
            }
        }
    }

    private void asyncLocations(OrderBuilder b) {
        if (!ctx.a.decoratorPresent) {
            for (MethodInfo m : ctx.a.index.methods()) {
                if (m.decl().getLeadingAnnotations().stream()
                        .anyMatch(an -> Annotations.is(an, "org.springframework.scheduling.annotation.Async"))) {
                    b.location(m.file().relPath(), m.line(), Rules.ASYNC_CONTEXT_LOSS);
                }
            }
        }
    }

    // ---- vendor mode: copy the support classes into the service --------------------------------

    static final List<String> VENDORED = List.of("BatchTraceListener", "ContextExecutors", "ContextPropagation",
            "LoggingDefaultsEnvironmentPostProcessor", "LoggingSupportAutoConfiguration", "MdcHygiene", "MdcScope",
            "ObservationRegistryHolder", "ObservedContext", "TraceContextEnv");
    /** Only copied when the service is a servlet application (they need jakarta.servlet and spring-web). */
    static final List<String> VENDORED_SERVLET = List.of("MdcHygieneFilter", "MdcHygieneConfiguration");
    /** Only copied when Micrometer Tracing is on the classpath. */
    static final List<String> VENDORED_TRACING = List.of("TraceContextEnvConfiguration");
    /** Only copied when spring-tx is on the classpath (JdbcTemplate brings it). */
    static final List<String> VENDORED_TX = List.of("AfterCommit");
    /** Only copied when Micrometer metrics are on the classpath. */
    static final List<String> VENDORED_METRICS = List.of("Outcomes");

    private boolean hasMetrics() {
        return ctx.a.project.probe().hasClass("io.micrometer.core.instrument.Metrics");
    }

    private List<String> optionalVendored() {
        List<String> out = new ArrayList<>();
        if (hasTx()) {
            out.addAll(VENDORED_TX);
        }
        if (hasMetrics()) {
            out.addAll(VENDORED_METRICS);
        }
        return out;
    }

    private boolean hasTx() {
        return ctx.a.project.probe().hasClass("org.springframework.transaction.support.TransactionSynchronizationManager");
    }
    private static final Pattern AUTO_CONFIGURATION = Pattern.compile("@AutoConfiguration(\\([^)]*\\))?", Pattern.DOTALL);

    private void vendor(String buildRel, String buildText, boolean kts) {
        String pkg = ctx.a.supportPackage;
        Path javaRoot = ctx.a.index.classes().stream().map(c -> sourceRoot(c.file().absPath())).filter(Objects::nonNull)
                .findFirst().orElse(root.resolve("src/main/java"));
        Path dir = javaRoot.resolve(pkg.replace('.', '/'));
        OrderBuilder b = ctx.order(Rules.SETUP_SUPPORT_STARTER, "vendor|" + pkg).at(rel(dir.resolve("ObservedContext.java")), 1)
                .title("Copy the logging support classes into " + pkg + " (vendor mode, no library dependency)")
                .suggestion("Generated code imports " + pkg + ".{ObservedContext, MdcScope, ContextExecutors}; the TaskDecorator, "
                        + "batch trace listener and ECS defaults are registered by component scan and spring.factories. "
                        + "Switching to the Nexus starter later = delete this package and set support.mode: dependency.");
        List<String> classes = new ArrayList<>(VENDORED);
        if (ctx.a.project.probe().hasClass("jakarta.servlet.Filter")
                && ctx.a.project.probe().hasClass("org.springframework.web.filter.OncePerRequestFilter")) {
            classes.addAll(VENDORED_SERVLET);
        }
        if (ctx.a.project.probe().hasClass("io.micrometer.tracing.Tracer")) {
            classes.addAll(VENDORED_TRACING);
        }
        classes.addAll(optionalVendored());
        for (String cls : classes) {
            Path target = dir.resolve(cls + ".java");
            if (Files.exists(target)) {
                b.suggestion(b.suggestion + " " + rel(target) + " already exists and was left as is.");
                continue;
            }
            b.edit(Edit.create(rel(target), vendoredSource(cls, pkg)));
        }
        Path factories = resourcesDir(javaRoot.resolve("x.java")).resolve("META-INF/spring.factories");
        String entry = "org.springframework.boot.EnvironmentPostProcessor=\\\n  " + pkg + ".LoggingDefaultsEnvironmentPostProcessor\n";
        if (!Files.exists(factories)) {
            b.edit(Edit.create(rel(factories), entry));
        } else {
            String existing = read(factories);
            if (existing.contains("org.springframework.boot.EnvironmentPostProcessor")) {
                b.suggestion(b.suggestion + " Add " + pkg + ".LoggingDefaultsEnvironmentPostProcessor to the existing "
                        + "EnvironmentPostProcessor entry in " + rel(factories) + ".");
            } else {
                b.edit(Edit.insert(rel(factories), existing.length(), (existing.endsWith("\n") || existing.isEmpty() ? "" : "\n") + entry));
            }
        }
        // the copied code needs these; Spring Boot's BOM manages the versions
        for (var dep : Map.of("io.micrometer.observation.Observation", "io.micrometer:micrometer-observation",
                "io.micrometer.context.ContextSnapshotFactory", "io.micrometer:context-propagation").entrySet()) {
            if (!ctx.a.project.probe().hasClass(dep.getKey()) && !buildText.contains(dep.getValue())) {
                b.edit(dependencyInsert(buildRel, buildText, kts, dep.getValue()));
            }
        }
        asyncLocations(b);
        ctx.supportOrderId = b.id();
    }

    /**
     * Copied support classes that differ from the current version are replaced (they are logast's code, and newer
     * generated code needs the newer API). The dry run shows the diff; edits made to the copies show up there too.
     */
    private void updateVendored() {
        var oc = ctx.a.index.clazz(ctx.a.supportPackage + ".ObservedContext");
        if (oc == null) {
            return;
        }
        Path dir = oc.file().absPath().getParent();
        List<String> classes = new ArrayList<>(VENDORED);
        classes.addAll(VENDORED_SERVLET);
        classes.addAll(VENDORED_TRACING);
        classes.addAll(VENDORED_TX);
        classes.addAll(VENDORED_METRICS);
        OrderBuilder b = null;
        List<String> changed = new ArrayList<>();
        for (String cls : classes) {
            Path target = dir.resolve(cls + ".java");
            if (!Files.exists(target)) {
                continue;
            }
            String current = vendoredSource(cls, ctx.a.supportPackage);
            String existing = read(target);
            if (existing.strip().equals(current.strip())) {
                continue;
            }
            if (b == null) {
                b = ctx.order(Rules.SETUP_SUPPORT_STARTER, "vendor-update|" + ctx.a.supportPackage).at(rel(target), 1);
            }
            b.edit(Edit.replace(rel(target), 0, existing.length(), current));
            changed.add(cls);
        }
        // classes newer logast versions added (AfterCommit, …)
        for (String cls : optionalVendored()) {
            Path target = dir.resolve(cls + ".java");
            if (Files.exists(target)) {
                continue;
            }
            if (b == null) {
                b = ctx.order(Rules.SETUP_SUPPORT_STARTER, "vendor-update|" + ctx.a.supportPackage).at(rel(target), 1);
            }
            b.edit(Edit.create(rel(target), vendoredSource(cls, ctx.a.supportPackage)));
            changed.add(cls + " (new)");
        }
        if (b != null) {
            b.title("Update the copied logging support classes to this logast version: " + String.join(", ", changed))
                    .suggestion("They are logast's own code, copied in vendor mode; newer generated code (e.g. ObservedContext.run) "
                            + "needs the newer version. Check the diff if you edited them.");
            ctx.supportUpdateOrderId = b.id();
        }
    }

    /** Starter source with its package rewritten; the auto-configuration becomes a component-scanned @Configuration. */
    static String vendoredSource(String cls, String pkg) {
        String src;
        try (var in = SetupRules.class.getResourceAsStream("/logast-vendor/" + cls + ".java")) {
            src = new String(Objects.requireNonNull(in, "missing vendored source " + cls).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        src = src.replace("package dev.logast.support;", "package " + pkg + ";");
        if (cls.equals("LoggingSupportAutoConfiguration") || cls.equals("MdcHygieneConfiguration")
                || cls.equals("TraceContextEnvConfiguration")) {
            src = AUTO_CONFIGURATION.matcher(src).replaceFirst("@Configuration(proxyBeanMethods = false)")
                    .replace("import org.springframework.boot.autoconfigure.AutoConfiguration;",
                            "import org.springframework.context.annotation.Configuration;");
        }
        return "// Copied by logast (support.mode: vendor) from logging-support. Keep in sync or switch to the starter.\n" + src;
    }

    private static Path sourceRoot(Path javaFile) {
        for (Path dir = javaFile.getParent(); dir != null; dir = dir.getParent()) {
            if (dir.endsWith(Path.of("src", "main", "java"))) {
                return dir;
            }
        }
        return null;
    }

    static Edit dependencyInsert(String rel, String text, boolean kts, String coordinates) {
        Matcher m = DEPENDENCIES.matcher(text);
        if (!m.find()) {
            return null;
        }
        int at = Text.nextLine(text, m.end());
        String indent = Text.blank(text, at, Text.lineEnd(text, at)) ? "    " : Text.indentAt(text, at);
        if (indent.isEmpty()) {
            indent = "    ";
        }
        String line = kts ? "implementation(\"" + coordinates + "\")" : "implementation '" + coordinates + "'";
        return Edit.insert(rel, at, indent + line + "\n");
    }

    // ---- logback ----------------------------------------------------------------------------

    private void logback(Path resources) {
        for (String name : List.of("logback-spring.xml", "logback.xml")) {
            Path xml = resources.resolve(name);
            if (!Files.exists(xml) || !ctx.enabled(Rules.SETUP_LOGBACK_XML)) {
                continue;
            }
            String rel = rel(xml);
            OrderBuilder b = ctx.order(Rules.SETUP_LOGBACK_XML, rel).at(rel, 1)
                    .title("Replace " + name + " with Spring Boot structured (ECS JSON) logging");
            LogbackSummary s = analyse(xml);
            if (!s.custom.isEmpty()) {
                b.suggestion("Not removed automatically, it has custom parts: " + String.join("; ", s.custom)
                        + ". Move these to logging.structured.* / logging.level.* properties or a JsonWriterStructuredLogFormatter, then delete the file.");
                continue;
            }
            b.edit(Edit.delete(rel));
            if (!s.levels.isEmpty()) {
                b.edits(levelMigration(resources, s.levels));
                b.suggestion("Logger levels moved to application configuration: " + s.levels);
            }
        }
    }

    private record LogbackSummary(List<String> custom, Map<String, String> levels) {
    }

    private LogbackSummary analyse(Path xml) {
        List<String> custom = new ArrayList<>();
        Map<String, String> levels = new LinkedHashMap<>();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(xml.toFile());
            for (Element el : children(doc.getDocumentElement())) {
                switch (el.getTagName()) {
                    case "include" -> {
                        if (!el.getAttribute("resource").startsWith("org/springframework/boot/logging/logback/")) {
                            custom.add("include " + el.getAttribute("resource") + el.getAttribute("file"));
                        }
                    }
                    case "appender" -> {
                        String cls = el.getAttribute("class");
                        if (!STANDARD_APPENDERS.contains(cls)) {
                            custom.add("appender " + el.getAttribute("name") + " (" + cls + ")");
                        }
                        for (Element child : children(el)) {
                            if ((child.getTagName().equals("encoder") || child.getTagName().equals("layout"))
                                    && !STANDARD_ENCODERS.contains(child.getAttribute("class"))) {
                                custom.add(child.getTagName() + " " + child.getAttribute("class"));
                            }
                        }
                    }
                    case "root" -> {
                        if (!el.getAttribute("level").isEmpty()) {
                            levels.put("root", el.getAttribute("level"));
                        }
                    }
                    case "logger" -> {
                        if (!children(el).isEmpty() || "false".equals(el.getAttribute("additivity"))) {
                            custom.add("logger " + el.getAttribute("name") + " with own appenders");
                        } else if (!el.getAttribute("level").isEmpty()) {
                            levels.put(el.getAttribute("name"), el.getAttribute("level"));
                        }
                    }
                    case "property", "springProperty", "contextName" -> {
                    }
                    default -> custom.add("<" + el.getTagName() + ">");
                }
            }
        } catch (Exception e) {
            custom.add("unparseable XML: " + e.getMessage());
        }
        return new LogbackSummary(custom, levels);
    }

    private static List<Element> children(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) nl.item(i));
            }
        }
        return out;
    }

    private List<Edit> levelMigration(Path resources, Map<String, String> levels) {
        Path yml = Files.exists(resources.resolve("application.yaml")) ? resources.resolve("application.yaml")
                : resources.resolve("application.yml");
        Path props = resources.resolve("application.properties");
        if (!Files.exists(yml) && Files.exists(props)) {
            String text = read(props);
            StringBuilder add = new StringBuilder(text.endsWith("\n") || text.isEmpty() ? "" : "\n");
            levels.forEach((k, v) -> {
                if (!text.contains("logging.level." + k + "=")) {
                    add.append("logging.level.").append(k).append('=').append(v).append('\n');
                }
            });
            return List.of(Edit.insert(rel(props), text.length(), add.toString()));
        }
        StringBuilder snippet = new StringBuilder("logging:\n  level:\n");
        levels.forEach((k, v) -> snippet.append("    ").append(k).append(": ").append(v).append('\n'));
        return List.of(Edit.yamlMerge(rel(yml), snippet.toString(), false));
    }

    /** Deep-merges {@code snippet} into the YAML document, keeping existing values (OpenRewrite MergeYamlVisitor). */
    static String mergeYaml(String existing, String snippet) {
        return mergeYaml(existing, snippet, false);
    }

    /** @param overwrite true: the snippet's values replace existing ones (a fix that must change a value) */
    public static String mergeYaml(String existing, String snippet, boolean overwrite) {
        Yaml.Documents docs = (Yaml.Documents) new YamlParser().parse(existing).findFirst().orElseThrow();
        Yaml.Document doc = docs.getDocuments().getFirst();
        Yaml incoming = ((Yaml.Documents) new YamlParser().parse(snippet).findFirst().orElseThrow())
                .getDocuments().getFirst().getBlock();
        Yaml merged = new MergeYamlVisitor<Integer>(doc.getBlock(), incoming, !overwrite, null, null, null)
                .visitNonNull(doc.getBlock(), 0, new Cursor(new Cursor(new Cursor(null, Cursor.ROOT_VALUE), docs), doc));
        List<Yaml.Document> all = new ArrayList<>(docs.getDocuments());
        all.set(0, doc.withBlock((Yaml.Block) merged));
        String out = dropCopiedComments(existing, docs.withDocuments(all).printAll());
        return out.endsWith("\n") ? out : out + "\n";
    }

    /**
     * The merge carries the trailing comment of the document's last line onto blocks it appends ("root: INFO   # old
     * comment"). A comment on a line the merge added, copied from an existing line, is removed again.
     */
    static String dropCopiedComments(String before, String after) {
        Set<String> oldLines = new HashSet<>(before.lines().toList());
        Set<String> oldComments = new HashSet<>();
        for (String l : before.lines().toList()) {
            int i = l.indexOf(" #");
            if (i >= 0) {
                oldComments.add(l.substring(i).strip());
            }
        }
        StringBuilder out = new StringBuilder();
        for (String l : after.split("\n", -1)) {
            int i = l.indexOf(" #");
            if (i >= 0 && !oldLines.contains(l) && oldComments.contains(l.substring(i).strip())
                    && !before.contains(l.substring(0, i).stripTrailing() + l.substring(i, l.length()))) {
                l = l.substring(0, i).stripTrailing();
            }
            out.append(l).append('\n');
        }
        return out.substring(0, out.length() - 1);
    }

    // ---- service identity: name and version on every log line, meter and span -----------------

    /**
     * Every log line, meter and span says which service and version wrote it. Spring Boot fills ECS/GELF log fields and
     * the OTel resource from spring.application.name/version; Logstash JSON and meters need it spelled out.
     */
    private void serviceIdentity() {
        if (!ctx.enabled(Rules.SETUP_SERVICE_IDENTITY)) {
            return;
        }
        Map<String, String> props = dev.logast.SpringProperties.read(root);
        StringBuilder yaml = new StringBuilder();
        List<String> what = new ArrayList<>();
        boolean name = !props.containsKey("spring.application.name");
        boolean version = !props.containsKey("spring.application.version");
        if (name || version) {
            yaml.append("spring:\n  application:\n");
            if (name) {
                String n = ctx.policy.logging().serviceName() != null ? ctx.policy.logging().serviceName() : projectName();
                yaml.append("    name: ").append(yamlValue(n)).append('\n');
                what.add("spring.application.name");
            }
            if (version) {
                yaml.append("    version: ").append(yamlValue(ctx.policy.logging().serviceVersion())).append('\n');
                what.add("spring.application.version");
            }
        }
        String policyFormat = ctx.policy.logging().structuredFormat();
        // a non-default logast.yaml format wins (the format rule switches the service to it); else what the service has
        String format = !policyFormat.equals("ecs") && !policyFormat.equals("none") ? policyFormat
                : props.getOrDefault("logging.structured.format.console", policyFormat);
        if (format.equals("ecs")) {
            String used = dev.logast.ServiceSetup.detectLogFormat(root);
            format = used != null ? used : format;
        }
        boolean logstashFields = format.equals("logstash") && props.keySet().stream().noneMatch(k -> k.startsWith("logging.structured.json.add"));
        boolean meters = ctx.a.project.probe().hasClass("io.micrometer.core.instrument.MeterRegistry")
                && props.keySet().stream().noneMatch(k -> k.startsWith("management.metrics.tags."));
        boolean otel = ctx.a.project.probe().hasClass("io.opentelemetry.api.OpenTelemetry")
                && props.keySet().stream().noneMatch(k -> k.startsWith("management.opentelemetry.resource-attributes"));
        if (logstashFields) {
            yaml.append("logging:\n  structured:\n    json:\n      add:\n")
                    .append("        service:\n          name: ${spring.application.name}\n          version: ${spring.application.version}\n");
            what.add("service.name/version in Logstash JSON");
        }
        if (meters || otel) {
            yaml.append("management:\n");
            if (meters) {
                yaml.append("  metrics:\n    tags:\n      application: ${spring.application.name}\n      version: ${spring.application.version}\n");
                what.add("application/version tags on every meter");
            }
            if (otel) {
                yaml.append("  opentelemetry:\n    resource-attributes:\n      service:\n        version: ${spring.application.version}\n");
                what.add("service.version on spans and OTLP data");
            }
        }
        if (what.isEmpty()) {
            return;
        }
        String where = ServiceYaml.path(ctx);
        OrderBuilder b = ctx.order(Rules.SETUP_SERVICE_IDENTITY, "identity|" + where + "|" + String.join(",", what)).at(where, 1)
                .title("Logs and metrics do not say which service and version wrote them: add " + String.join(", ", what))
                .suggestion("Env vars still win at runtime (SPRING_APPLICATION_NAME, SPRING_APPLICATION_VERSION). Set "
                        + "logging.serviceName / logging.serviceVersion in logast.yaml to the expressions your platform uses, "
                        + "e.g. serviceVersion: \"${BUILD_VERSION:unknown}\". ECS and GELF logs take both from spring.application.*.");
        ServiceYaml.merge(ctx, b, yaml.toString());
    }

    private String projectName() {
        for (String n : List.of("settings.gradle.kts", "settings.gradle")) {
            Path p = root.resolve(n);
            if (Files.exists(p)) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("rootProject\\.name\\s*=\\s*[\"']([^\"']+)").matcher(read(p));
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return root.getFileName().toString();
    }

    private static String yamlValue(String v) {
        return v.matches("[\\w.\\-]+|\\$\\{[^}]*}") ? v : "\"" + v.replace("\"", "\\\"") + "\"";
    }

    // ---- structured logging properties ------------------------------------------------------

    private void structuredLogging(Path resources) {
        if (!ctx.enabled(Rules.SETUP_STRUCTURED_LOGGING)) {
            return;
        }
        explicitJsonFormat(resources);
        for (String name : List.of("application.yml", "application.yaml", "application.properties")) {
            Path p = resources.resolve(name);
            if (!Files.exists(p)) {
                continue;
            }
            String text = read(p);
            Matcher m = Pattern.compile("(?m)(console:\\s*|logging\\.structured\\.format\\.console\\s*[=:]\\s*)([\\w.\\-]+)\\s*$").matcher(text);
            while (m.find()) {
                if (!JSON_FORMATS.contains(m.group(2)) && (name.endsWith(".properties") || text.contains("structured"))) {
                    ctx.order(Rules.SETUP_STRUCTURED_LOGGING, rel(p) + "|" + m.start()).at(rel(p), lineOf(text, m.start()))
                            .title("Console log format '" + m.group(2) + "' is not a JSON format")
                            .suggestion("Use ecs (default from logging-support), logstash or gelf, or remove the property.");
                }
            }
        }
    }

    /**
     * No {@code logging.structured.format.console} in any application file: write it, so the JSON log format is visible
     * in the service's own configuration (the starter's runtime default stays as a fallback).
     */
    private void explicitJsonFormat(Path resources) {
        String format = ctx.policy.logging().structuredFormat();
        // the built-in default (ecs) gives way to the format the service already uses (e.g. logstash-logback-encoder)
        String used = format.equals("ecs") ? dev.logast.ServiceSetup.detectLogFormat(root) : null;
        String why = "";
        if (used != null && !used.equals(format)) {
            format = used;
            why = ", the format the service already uses";
        }
        if (format.equals("none")) {
            return;
        }
        String configured = dev.logast.SpringProperties.read(root).get("logging.structured.format.console");
        if (configured != null && JSON_FORMATS.contains(configured) && !configured.equals(format) && !format.equals("ecs")) {
            // logast.yaml asks for another JSON format than the service has (e.g. ecs written by an earlier run)
            String where = ServiceYaml.path(ctx);
            OrderBuilder b = ctx.order(Rules.SETUP_STRUCTURED_LOGGING, "structured-format-change|" + where).at(where, 1)
                    .title("Console log format is " + configured + ", logast.yaml says " + format + ": switch it")
                    .suggestion("logging.structuredFormat in logast.yaml decides; set it to " + configured + " to keep the current one.");
            ServiceYaml.merge(ctx, b, "logging:\n  structured:\n    format:\n      console: " + format + "\n", true);
            return;
        }
        if (ServiceYaml.mentioned(ctx, "structured.format.console") || ServiceYaml.mentioned(ctx, "format:\n      console")) {
            return;
        }
        for (String name : List.of("application.yml", "application.yaml", "application.properties")) {
            Path p = resources.resolve(name);
            if (Files.exists(p) && read(p).matches("(?s).*structured:\\s*\\n\\s*format:\\s*\\n\\s*console:.*")) {
                return;
            }
        }
        Path props = resources.resolve("application.properties");
        boolean yml = Files.exists(resources.resolve("application.yml")) || Files.exists(resources.resolve("application.yaml"))
                || !Files.exists(props);
        String where = yml ? ServiceYaml.path(ctx) : rel(props);
        OrderBuilder b = ctx.order(Rules.SETUP_STRUCTURED_LOGGING, "structured-format|" + where).at(where, 1)
                .title("No structured log format in the service's config: logs are JSON only while the logging-support "
                        + "starter's default applies; make it explicit (" + format + why + ")")
                .suggestion("Sets logging.structured.format.console=" + format + ". Service name and version come from "
                        + "spring.application.name / spring.application.version.");
        if (yml) {
            ServiceYaml.merge(ctx, b, "logging:\n  structured:\n    format:\n      console: " + format + "\n");
        } else {
            String text = read(props);
            b.edit(Edit.insert(where, text.length(), (text.isEmpty() || text.endsWith("\n") ? "" : "\n")
                    + "logging.structured.format.console=" + format + "\n"));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Path findBuildFile(Path from) {
        for (Path dir = from; dir != null && dir.startsWith(root); dir = dir.getParent()) {
            for (String n : List.of("build.gradle.kts", "build.gradle")) {
                if (Files.exists(dir.resolve(n))) {
                    return dir.resolve(n);
                }
            }
        }
        return null;
    }

    private static Path resourcesDir(Path javaFile) {
        for (Path dir = javaFile.getParent(); dir != null; dir = dir.getParent()) {
            if (dir.endsWith(Path.of("src", "main", "java"))) {
                return dir.getParent().resolve("resources");
            }
        }
        return javaFile.getParent();
    }

    private String rel(Path p) {
        return root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    static int lineOf(String text, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
