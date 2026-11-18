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
    private static final Pattern BOOT_PLUGIN_VERSION = Pattern.compile(
            "\\bid\\s*(?:\\(\\s*)?[\\\"']org\\.springframework\\.boot[\\\"']\\s*\\)?\\s*version\\s*[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern BOOT_PLUGIN_CLASSPATH_VERSION = Pattern.compile(
            "org\\.springframework\\.boot:spring-boot-gradle-plugin:([0-9][^\\s\\\"']*)");
    private static final Pattern BOOT_CORE_JAR = Pattern.compile("^spring-boot-([0-9]+\\..*)\\.jar$");
    private static final String BOOT4_OTEL_STARTER = "spring-boot-starter-opentelemetry";
    private static final String ACTUATOR_STARTER = "spring-boot-starter-actuator";
    private static final String OTEL_BRIDGE = "micrometer-tracing-bridge-otel";
    private static final String BRAVE_BRIDGE = "micrometer-tracing-bridge-brave";
    private static final String OTEL_EXPORTER = "opentelemetry-exporter-otlp";
    private static final String OTEL_SPRING_STARTER = "opentelemetry-spring-boot-starter";
    private static final String OTEL_JAVA_AGENT = "opentelemetry-javaagent";
    private static final Pattern OTEL_AGENT_ARG = Pattern.compile("(?i)-javaagent:[^\\s\"')]*opentelemetry");
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
        // the dependencies may live in a script the build file applies (apply from: 'gradle/dependencies.gradle', #80)
        BuildScripts scripts = BuildScripts.of(root, buildFile);
        String text = scripts.text();
        String rel = rel(buildFile);

        String support = ctx.policy.support().coordinates();
        // only rules that need a single class selected (errors alone): vendor mode leaves the copy to them, the
        // starter dependency comes only when such a fix applies
        boolean core = Rules.coreSupportSelected(ctx.policy);
        if (ctx.policy.support().vendor()) {
            if (!ctx.a.supportStarterPresent && ctx.enabled(Rules.SETUP_SUPPORT_STARTER) && core) {
                vendor(scripts);
            } else if (ctx.a.supportStarterPresent && ctx.enabled(Rules.SETUP_SUPPORT_STARTER)) {
                updateVendored();
                featuresOfPresentStarter();
            }
        } else if (!ctx.a.supportStarterPresent && !text.contains(ctx.policy.support().groupAndArtifact())
                && ctx.enabled(Rules.SETUP_SUPPORT_STARTER) && (core || ErrorRules.untracedFixable(ctx))) {
            OrderBuilder b = ctx.order(Rules.SETUP_SUPPORT_STARTER, "support|" + rel).at(rel, 1)
                    .title("Add the logging-support starter (" + support + ")")
                    .suggestion("Provides ObservedContext, MdcScope, ContextExecutors and the support classes generated code uses.");
            BuildScripts.Insert e = scripts.insert(support);
            if (e == null) {
                b.suggestion(b.suggestion + " No top-level dependencies {} block found: add it manually." + scripts.unresolvedNote());
            } else {
                b.edit(e.edit()).at(e.file(), e.line());
            }
            switchOn(b, supportFeatures());
            asyncLocations(b);
            ctx.supportOrderId = b.id();
        } else if (!ctx.policy.support().vendor() && ctx.enabled(Rules.SETUP_SUPPORT_STARTER)
                && (ctx.a.supportStarterPresent || text.contains(ctx.policy.support().groupAndArtifact()))) {
            featuresOfPresentStarter();   // on the classpath, or on the build file and not yet on the classpath logast reads
        }

        if (ctx.enabled(Rules.SETUP_OTEL_STARTER)) {
            int bootMajor = springBootMajor(buildFile);
            List<String> compatibility = otelCompatibilityWarnings(text, bootMajor);
            if (!compatibility.isEmpty()) {
                ctx.order(Rules.SETUP_OTEL_STARTER, "otel-compat|" + rel).at(rel, 1)
                        .title("Review the existing Micrometer and OpenTelemetry setup")
                        .suggestion(String.join(" ", compatibility) + " No dependency changes were made.");
                return;
            }
            List<String> coordinates = bootMajor == 3
                    ? List.of("org.springframework.boot:" + ACTUATOR_STARTER, "io.micrometer:" + OTEL_BRIDGE,
                            "io.opentelemetry:" + OTEL_EXPORTER)
                    : bootMajor >= 4 ? List.of("org.springframework.boot:" + BOOT4_OTEL_STARTER) : List.of();
            List<String> missing = coordinates.stream().filter(coordinate ->
                    !dependencyPresent(text, coordinate, bootMajor)).toList();
            if (!coordinates.isEmpty() && !missing.isEmpty()) {
                String title = bootMajor == 3
                        ? "Add Spring Boot 3 Micrometer OpenTelemetry tracing dependencies"
                        : "Add " + BOOT4_OTEL_STARTER + " (tracing + trace/span ids in logs)";
                OrderBuilder b = ctx.order(Rules.SETUP_OTEL_STARTER, "otel|" + rel).at(rel, 1).title(title);
                BuildScripts.Insert e = scripts.insert(missing);
                if (e == null) {
                    b.suggestion("No top-level dependencies {} block found: add them manually." + scripts.unresolvedNote());
                } else {
                    b.edit(e.edit()).at(e.file(), e.line());
                }
            } else if (coordinates.isEmpty()) {
                ctx.order(Rules.SETUP_OTEL_STARTER, "otel|" + rel).at(rel, 1)
                        .title("Check the OpenTelemetry tracing dependencies")
                        .suggestion("Could not identify a supported Spring Boot version; choose the tracing dependencies manually.");
            }
        }
    }

    // ---- the starter's behaviours, switched on per pack (#93) ---------------------------------------

    /**
     * The starter's behaviours the selected packs use and the service does not configure yet, property → what it does.
     * Each is off until its property is true, so the starter added for one pack (errors needs only ProblemTraceId)
     * changes nothing another pack owns: the log format and MDC hygiene are logging's, context propagation logging's
     * and tracing's (ASYNC_CONTEXT_LOSS), the batch trace tracing's.
     */
    private Map<String, String> supportFeatures() {
        Map<String, String> out = new LinkedHashMap<>();
        if (ctx.enabled(Rules.SETUP_STRUCTURED_LOGGING)) {
            out.put(LOG_DEFAULTS, "ECS JSON console logs by default, service.name/version from SERVICE_NAME/APP_VERSION");
        }
        if (ctx.policy.packSelected("logging") && servlet()) {
            out.put(MDC_HYGIENE, "MdcHygieneFilter clears and reports MDC leaking across requests");
        }
        if (ctx.enabled(Rules.ASYNC_CONTEXT_LOSS)) {
            out.put(CONTEXT_PROPAGATION, "a TaskDecorator carries MDC and trace context into @Async and Spring executors");
        }
        if (ctx.policy.packSelected("tracing")) {
            out.put(BATCH_TRACE, "batch runs continue the trace from TRACEPARENT / TRACE_ID");
        }
        out.keySet().removeIf(ctx.a.springProperties::containsKey);   // a value the service set (true or false) stays
        return out;
    }

    static final String LOG_DEFAULTS = "logging-support.log-defaults.enabled";
    static final String MDC_HYGIENE = "logging-support.mdc-hygiene.enabled";
    static final String CONTEXT_PROPAGATION = "logging-support.context-propagation.enabled";
    static final String BATCH_TRACE = "logging-support.batch-trace.enabled";

    /** Writes the features' properties into the service's configuration, as part of {@code b}. */
    private void switchOn(OrderBuilder b, Map<String, String> features) {
        if (features.isEmpty()) {
            return;
        }
        StringBuilder yaml = new StringBuilder("logging-support:\n");
        features.keySet().forEach(k -> yaml.append("  ").append(k.split("\\.")[1]).append(":\n    enabled: true\n"));
        ServiceYaml.merge(ctx, b, yaml.toString());
        b.suggestion(b.suggestion + " Switches on what the selected packs use: " + String.join("; ", features.values()) + ".");
    }

    /**
     * The starter (or its copy) is already there: its behaviours the selected packs use are switched on by their
     * properties. A service that took the starter before #93 got them by default; this keeps them after its upgrade.
     */
    private void featuresOfPresentStarter() {
        Map<String, String> features = supportFeatures();
        if (features.isEmpty()) {
            return;
        }
        String where = ServiceYaml.path(ctx);
        OrderBuilder b = ctx.order(Rules.SETUP_SUPPORT_STARTER, "support-features|" + where).at(where, 1)
                .title("Switch on the logging-support behaviours the selected packs use: " + String.join(", ", features.keySet()))
                .suggestion("The starter switches nothing on by itself.");
        switchOn(b, features);
        if (features.containsKey(CONTEXT_PROPAGATION)) {
            asyncLocations(b);
        }
    }

    private boolean servlet() {
        return ctx.a.project.probe().hasClass("jakarta.servlet.Filter")
                && ctx.a.project.probe().hasClass("org.springframework.web.filter.OncePerRequestFilter");
    }

    private void asyncLocations(OrderBuilder b) {
        if (!ctx.a.decoratorPresent && ctx.enabled(Rules.ASYNC_CONTEXT_LOSS)) {   // a switched-off rule is not listed (#75)
            for (MethodInfo m : ctx.a.index.methods()) {
                if (m.decl().getLeadingAnnotations().stream()
                        .anyMatch(an -> Annotations.is(an, "org.springframework.scheduling.annotation.Async"))
                        && !ctx.ignored(m.file(), m.line(), Rules.ASYNC_CONTEXT_LOSS)) {
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
    /** Only copied for Spring MVC with Micrometer Tracing, when the errors pack's ERROR_RESPONSE_UNTRACED is on. */
    static final List<String> VENDORED_ERRORS = List.of("ProblemTraceId");   // component scan finds it; no configuration
    /** Its WebFlux counterpart, copied instead on a WebFlux-only service (#103). */
    static final List<String> VENDORED_ERRORS_REACTIVE = List.of("ReactiveProblemTraceId");
    /**
     * Only copied when the service's own code uses BusinessBaggage (#16): logast never generates that call, so the
     * helper arrives the moment someone writes it; its Tracer wiring only with Micrometer Tracing on the classpath.
     */
    static final List<String> VENDORED_BAGGAGE = List.of("BusinessBaggage", "BusinessBaggageConfiguration");
    private static final Pattern BUSINESS_BAGGAGE = Pattern.compile("\\bBusinessBaggage\\b");

    private boolean usesBusinessBaggage() {
        return ctx.a.index.classes().stream().filter(c -> !c.fqn().startsWith(ctx.a.supportPackage + "."))
                .map(c -> c.file()).distinct().anyMatch(f -> BUSINESS_BAGGAGE.matcher(f.text()).find());
    }

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
        if (ctx.enabled(Rules.ERROR_RESPONSE_UNTRACED) && ErrorRules.traceableErrors(ctx)) {
            out.addAll(ErrorRules.webfluxOnly(ctx) ? VENDORED_ERRORS_REACTIVE : VENDORED_ERRORS);
        }
        if (usesBusinessBaggage()) {
            out.add(VENDORED_BAGGAGE.get(0));
            if (ctx.a.project.probe().hasClass("io.micrometer.tracing.Tracer")) {
                out.add(VENDORED_BAGGAGE.get(1));
            }
        }
        return out;
    }

    private boolean hasTx() {
        return ctx.a.project.probe().hasClass("org.springframework.transaction.support.TransactionSynchronizationManager");
    }
    private static final Pattern AUTO_CONFIGURATION = Pattern.compile("@AutoConfiguration(\\([^)]*\\))?", Pattern.DOTALL);

    /** Spring Boot 4's EnvironmentPostProcessor; Boot 3 has it in org.springframework.boot.env. */
    static final String BOOT4_EPP = "org.springframework.boot.EnvironmentPostProcessor";
    static final String BOOT3_EPP = "org.springframework.boot.env.EnvironmentPostProcessor";

    /** Boot 3's interface only when the classpath has it and not Boot 4's; else (Boot 4, or unknown) Boot 4's. */
    static String environmentPostProcessor(boolean boot4OnClasspath, boolean boot3OnClasspath) {
        return !boot4OnClasspath && boot3OnClasspath ? BOOT3_EPP : BOOT4_EPP;
    }

    private void vendor(BuildScripts scripts) {
        String pkg = ctx.a.supportPackage;
        // #116: the interface (and spring.factories key) of the service's Spring Boot line: Boot 4 moved it
        String epp = environmentPostProcessor(ctx.a.project.probe().hasClass(BOOT4_EPP), ctx.a.project.probe().hasClass(BOOT3_EPP));
        Path javaRoot = ctx.a.index.classes().stream().map(c -> sourceRoot(c.file().absPath())).filter(Objects::nonNull)
                .findFirst().orElse(root.resolve("src/main/java"));
        Path dir = javaRoot.resolve(pkg.replace('.', '/'));
        OrderBuilder b = ctx.order(Rules.SETUP_SUPPORT_STARTER, "vendor|" + pkg).at(rel(dir.resolve("ObservedContext.java")), 1)
                .title("Copy the logging support classes into " + pkg + " (vendor mode, no library dependency)")
                .suggestion("Generated code imports " + pkg + ".{ObservedContext, MdcScope, ContextExecutors}; the TaskDecorator, "
                        + "batch trace listener and ECS defaults are registered by component scan and spring.factories, each "
                        + "behind its logging-support.*.enabled property. "
                        + "Switching to the Nexus starter later = delete this package and set support.mode: dependency.");
        List<String> classes = new ArrayList<>(VENDORED);
        if (servlet()) {
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
            String src = vendoredSource(cls, pkg);
            if (cls.equals("LoggingDefaultsEnvironmentPostProcessor") && !epp.equals(BOOT4_EPP)) {
                src = src.replace("import " + BOOT4_EPP + ";", "import " + epp + ";");   // #116: Spring Boot 3's package
            }
            b.edit(Edit.create(rel(target), src));
        }
        Path factories = resourcesDir(javaRoot.resolve("x.java")).resolve("META-INF/spring.factories");
        String entry = epp + "=\\\n  " + pkg + ".LoggingDefaultsEnvironmentPostProcessor\n";
        if (!Files.exists(factories)) {
            b.edit(Edit.create(rel(factories), entry));
        } else {
            String existing = read(factories);
            if (existing.contains(epp + "=")) {
                b.suggestion(b.suggestion + " Add " + pkg + ".LoggingDefaultsEnvironmentPostProcessor to the existing "
                        + "EnvironmentPostProcessor entry in " + rel(factories) + ".");
            } else {
                b.edit(Edit.insert(rel(factories), existing.length(), (existing.endsWith("\n") || existing.isEmpty() ? "" : "\n") + entry));
            }
        }
        // the copied code needs these; Spring Boot's BOM manages the versions
        for (var dep : Map.of("io.micrometer.observation.Observation", "io.micrometer:micrometer-observation",
                "io.micrometer.context.ContextSnapshotFactory", "io.micrometer:context-propagation").entrySet()) {
            if (!ctx.a.project.probe().hasClass(dep.getKey()) && !scripts.text().contains(dep.getValue())) {
                BuildScripts.Insert e = scripts.insert(dep.getValue());
                b.edit(e == null ? null : e.edit());
            }
        }
        switchOn(b, supportFeatures());
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
        classes.addAll(VENDORED_ERRORS);
        classes.addAll(VENDORED_ERRORS_REACTIVE);
        classes.addAll(VENDORED_BAGGAGE);
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
                || cls.equals("TraceContextEnvConfiguration") || cls.equals("BusinessBaggageConfiguration")) {
            src = AUTO_CONFIGURATION.matcher(src).replaceFirst("@Configuration(proxyBeanMethods = false)")
                    .replace("import org.springframework.boot.autoconfigure.AutoConfiguration;",
                            "import org.springframework.context.annotation.Configuration;");
        }
        return "// Copied by logast (support.mode: vendor) from logging-support. Keep in sync or switch to the starter.\n"
                + "// Source: logast, https://github.com/magyar-attila-317/logast (Apache License 2.0)\n" + src;
    }

    /**
     * One support class copied on its own (vendor mode), for a pack that needs only it without the logging set-up (the
     * errors pack's ProblemTraceId): the edit, or null when the file is there already.
     */
    static Edit vendorOne(RuleContext ctx, String cls) {
        Path root = ctx.a.project.root();
        Path javaRoot = ctx.a.index.classes().stream().map(c -> sourceRoot(c.file().absPath())).filter(Objects::nonNull)
                .findFirst().orElse(root.resolve("src/main/java"));
        Path target = javaRoot.resolve(ctx.a.supportPackage.replace('.', '/')).resolve(cls + ".java");
        if (Files.exists(target)) {
            return null;
        }
        return Edit.create(root.relativize(target).toString().replace('\\', '/'), vendoredSource(cls, ctx.a.supportPackage));
    }

    private static Path sourceRoot(Path javaFile) {
        for (Path dir = javaFile.getParent(); dir != null; dir = dir.getParent()) {
            if (dir.endsWith(Path.of("src", "main", "java"))) {
                return dir;
            }
        }
        return null;
    }

    /** Spring Boot 3 uses Micrometer's OTel bridge; the dedicated starter is for Boot 4+. */
    private int springBootMajor(Path buildFile) {
        return major(springBootVersion(buildFile));
    }

    /**
     * {major, minor} of the Spring Boot version the service builds with, {0, 0} when unknown: the Boot plugin's version
     * in the build file, its settings or version catalog, else the one spring-boot jar on the classpath. Property names
     * change between versions (spring.http.client.* in 3.4/3.5, spring.http.clients.* from 4.0), so other packs ask too.
     */
    static int[] springBootVersion(RuleContext ctx) {
        SetupRules s = new SetupRules(ctx);
        Path start = ctx.a.index.classes().stream()
                .filter(c -> c.decl().getLeadingAnnotations().stream()
                        .anyMatch(an -> Annotations.is(an, "org.springframework.boot.autoconfigure.SpringBootApplication")))
                .findFirst().map(c -> c.file().absPath().getParent()).orElse(s.root);
        Path buildFile = s.findBuildFile(start);
        String version = buildFile != null ? s.springBootVersion(buildFile) : s.classpathBootVersion();
        return new int[]{major(version), minor(version)};
    }

    private String springBootVersion(Path buildFile) {
        for (Path dir = buildFile.getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
            for (String name : List.of("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle")) {
                Path candidate = dir.resolve(name);
                if (!Files.isRegularFile(candidate)) {
                    continue;
                }
                String version = bootVersionFromBuild(BuildScripts.of(root, candidate).text());
                if (major(version) > 0) {
                    return version;
                }
            }
            Path catalog = dir.resolve("gradle/libs.versions.toml");
            if (Files.isRegularFile(catalog)) {
                String version = bootVersionFromCatalog(read(catalog));
                if (major(version) > 0) {
                    return version;
                }
            }
        }
        return classpathBootVersion();
    }

    private static String bootVersionFromBuild(String text) {
        Matcher plugin = BOOT_PLUGIN_VERSION.matcher(text);
        if (plugin.find()) {
            return plugin.group(1);
        }
        Matcher classpath = BOOT_PLUGIN_CLASSPATH_VERSION.matcher(text);
        return classpath.find() ? classpath.group(1) : null;
    }

    private static String bootVersionFromCatalog(String text) {
        Matcher plugin = Pattern.compile("(?m)^[\\w.-]+\\s*=\\s*\\{[^\\n]*id\\s*=\\s*[\\\"']org\\.springframework\\.boot[\\\"'][^\\n]*}")
                .matcher(text);
        if (!plugin.find()) {
            return null;
        }
        Matcher direct = Pattern.compile("version\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']").matcher(plugin.group());
        if (direct.find()) {
            return direct.group(1);
        }
        Matcher ref = Pattern.compile("version\\.ref\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']").matcher(plugin.group());
        if (!ref.find()) {
            return null;
        }
        Matcher version = Pattern.compile("(?m)^\\s*" + Pattern.quote(ref.group(1))
                + "\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']").matcher(text);
        return version.find() ? version.group(1) : null;
    }

    private static int major(String version) {
        Matcher m = Pattern.compile("^(\\d+)(?:\\.|$)").matcher(version == null ? "" : version);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static int minor(String version) {
        Matcher m = Pattern.compile("^\\d+\\.(\\d+)").matcher(version == null ? "" : version);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private int classpathBootMajor() {
        return major(classpathBootVersion());
    }

    /** The version of the spring-boot jar on the classpath when all of them agree on major.minor, else null. */
    private String classpathBootVersion() {
        Map<String, String> versions = new TreeMap<>();
        for (Path path : ctx.a.project.classpath()) {
            Matcher m = BOOT_CORE_JAR.matcher(path.getFileName().toString());
            if (m.matches()) {
                versions.put(major(m.group(1)) + "." + minor(m.group(1)), m.group(1));
            }
        }
        return versions.size() == 1 ? versions.values().iterator().next() : null;
    }

    private boolean dependencyPresent(String text, String coordinate, int bootMajor) {
        String artifact = coordinate.substring(coordinate.indexOf(':') + 1);
        return artifactPresent(text, artifact, bootMajor);
    }

    private List<String> otelCompatibilityWarnings(String text, int bootMajor) {
        List<String> warnings = new ArrayList<>();
        boolean boot4Starter = artifactPresent(text, BOOT4_OTEL_STARTER, bootMajor);
        boolean otelBridge = artifactPresent(text, OTEL_BRIDGE, bootMajor);
        boolean braveBridge = artifactPresent(text, BRAVE_BRIDGE, bootMajor);
        boolean communityStarter = artifactPresent(text, OTEL_SPRING_STARTER, bootMajor);
        // the agent jar itself must name OpenTelemetry: other agents (Mockito, JaCoCo) next to an OTel dependency are not it
        boolean javaAgent = text.contains(OTEL_JAVA_AGENT) || OTEL_AGENT_ARG.matcher(text).find()
                || artifactPresent(text, OTEL_JAVA_AGENT, bootMajor);

        if (bootMajor > 0 && bootMajor < 4 && boot4Starter) {
            warnings.add("spring-boot-starter-opentelemetry is the Spring Boot 4 starter and does not match this Spring Boot "
                    + bootMajor + " project. Remove it and use the Boot 3 Actuator, Micrometer OTel bridge, and OTLP exporter path.");
        }
        if (otelBridge && braveBridge) {
            warnings.add("Both Micrometer OTel and Brave tracer bridges are present; Micrometer supports only one tracer bridge. Choose one.");
        } else if (braveBridge) {
            warnings.add("A Micrometer Brave bridge is already present. Choose whether to keep Brave or switch to OpenTelemetry before adding another tracer.");
        }
        if (communityStarter) {
            warnings.add("The OpenTelemetry Spring Boot starter is already present. It is an alternative integration; review its compatibility before adding Spring Boot OpenTelemetry dependencies.");
        }
        if (javaAgent) {
            warnings.add("An OpenTelemetry Java agent is configured: it already instruments the application. Spring Boot or "
                    + "Micrometer OpenTelemetry dependencies next to it are a second instrumentation path; choose one.");
        }
        if (bootMajor >= 4 && !boot4Starter && otelBridge) {
            warnings.add("A Micrometer OpenTelemetry bridge is already present. Check its exporter configuration instead of adding a second starter.");
        }
        return warnings;
    }

    private boolean artifactPresent(String text, String artifact, int bootMajor) {
        if (text.contains(artifact)) {
            return true;
        }
        if (bootMajor <= 0 || bootMajor != classpathBootMajor()) {
            return false;
        }
        return ctx.a.project.classpath().stream().anyMatch(path -> path.getFileName().toString().startsWith(artifact + "-"));
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
        existing = dropEmptyKeys(existing, keyPaths(snippet));
        Yaml.Documents docs = (Yaml.Documents) new YamlParser().parse(existing).findFirst().orElseThrow();
        int target = targetDocument(docs, ServiceYaml.flatYaml(snippet).keySet(), overwrite);
        Yaml.Document doc = docs.getDocuments().get(target);
        Yaml incoming = ((Yaml.Documents) new YamlParser().parse(snippet).findFirst().orElseThrow())
                .getDocuments().getFirst().getBlock();
        Yaml merged = new MergeYamlVisitor<Integer>(doc.getBlock(), incoming, !overwrite, null, null, null)
                .visitNonNull(doc.getBlock(), 0, new Cursor(new Cursor(new Cursor(null, Cursor.ROOT_VALUE), docs), doc));
        List<Yaml.Document> all = new ArrayList<>(docs.getDocuments());
        all.set(target, doc.withBlock((Yaml.Block) merged));
        String out = dropCopiedComments(existing, docs.withDocuments(all).printAll());
        return out.endsWith("\n") ? out : out + "\n";
    }

    /**
     * The document a merge goes into (#47). Spring applies the documents of a file top to bottom, a later one overriding
     * an earlier one, and a document with {@code spring.config.activate.*} only for its profile or platform. A changed
     * value ({@code overwrite}) goes into the last unconditional document that defines one of the keys, so it is the one
     * Spring uses; anything else into the first unconditional document. Source: Spring Boot reference, "Externalized
     * Configuration", Working With Multi-Document Files (docs.spring.io/spring-boot/reference/features/external-config.html).
     */
    private static int targetDocument(Yaml.Documents docs, Set<String> keys, boolean overwrite) {
        int first = -1;
        int defining = -1;
        for (int i = 0; i < docs.getDocuments().size(); i++) {
            Set<String> defined = keyPaths(docs.withDocuments(List.of(docs.getDocuments().get(i))).printAll());
            if (defined.stream().anyMatch(k -> k.startsWith("spring.config.activate."))) {
                continue;
            }
            first = first < 0 ? i : first;
            if (overwrite && keys.stream().anyMatch(defined::contains)) {
                defining = i;
            }
        }
        return defining >= 0 ? defining : Math.max(first, 0);
    }

    /**
     * A key with nothing under it ("spring:" left after its children were removed) is null to Spring, the same as no
     * key. The merge keeps it as a value, though, and adds nothing below it: so an empty key the snippet fills is
     * dropped first, and the snippet's block takes its place.
     */
    static String dropEmptyKeys(String yaml, Set<String> fill) {
        List<String> lines = yaml.lines().toList();
        List<String> out = new ArrayList<>();
        Deque<Map.Entry<Integer, String>> path = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            java.util.regex.Matcher m = YAML_KEY.matcher(l);
            if (m.matches()) {
                int indent = m.group(1).length();
                while (!path.isEmpty() && path.peek().getKey() >= indent) {
                    path.pop();
                }
                String key = (path.isEmpty() ? "" : path.peek().getValue() + ".") + m.group(2);
                path.push(Map.entry(indent, key));
                if (m.group(3).isBlank() && fill.contains(key) && !hasChildren(lines, i, indent)) {
                    continue;
                }
            }
            out.add(l);
        }
        return String.join("\n", out) + (yaml.endsWith("\n") ? "\n" : "");
    }

    private static final java.util.regex.Pattern YAML_KEY = java.util.regex.Pattern.compile("^( *)([\\w.\\-]+):( *(?:#.*)?|\\s.*)$");

    private static boolean hasChildren(List<String> lines, int i, int indent) {
        for (int j = i + 1; j < lines.size(); j++) {
            String n = lines.get(j);
            if (n.isBlank() || n.stripLeading().startsWith("#")) {
                continue;
            }
            return n.length() - n.stripLeading().length() > indent || n.stripLeading().startsWith("- ");
        }
        return false;
    }

    /** Dotted paths of the keys a snippet sets ("spring", "spring.application", "spring.application.name"). */
    static Set<String> keyPaths(String yaml) {
        Set<String> out = new HashSet<>();
        Deque<Map.Entry<Integer, String>> path = new ArrayDeque<>();
        for (String l : yaml.lines().toList()) {
            java.util.regex.Matcher m = YAML_KEY.matcher(l);
            if (m.matches()) {
                int indent = m.group(1).length();
                while (!path.isEmpty() && path.peek().getKey() >= indent) {
                    path.pop();
                }
                String key = (path.isEmpty() ? "" : path.peek().getValue() + ".") + m.group(2);
                path.push(Map.entry(indent, key));
                out.add(key);
            }
        }
        return out;
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
        // #117: OpenTelemetry the build declares counts too: SETUP_OTEL_STARTER adds it in the same pass, and the classpath
        // logast reads only has it after apply refreshes it (else the next apply adds the resource attributes)
        Path buildFile = findBuildFile(root);
        String build = "";
        try {
            build = buildFile == null ? "" : Files.readString(buildFile);
        } catch (IOException | UncheckedIOException e) {
            // unreadable build file: the classpath decides
        }
        boolean otelDeclared = build.contains("spring-boot-starter-opentelemetry") || build.contains("opentelemetry-spring-boot-starter")
                || build.contains(OTEL_BRIDGE);
        boolean otel = (ctx.a.project.probe().hasClass("io.opentelemetry.api.OpenTelemetry") || otelDeclared)
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
        return dev.logast.parse.SourceText.readUnchecked(p);
    }
}
