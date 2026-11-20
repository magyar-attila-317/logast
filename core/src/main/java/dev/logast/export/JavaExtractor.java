package dev.logast.export;

import dev.logast.Analysis;
import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;
import dev.logast.fleet.NamedFleet;
import dev.logast.fleet.ServiceSurface;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.orders.ScanResult;
import dev.logast.parse.Pos;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #153: the facts logast's analysis already has about one service, for a knowledge base across repos (docs/facts.md):
 * the service, its entrypoints (endpoints, listeners, jobs), the calls it makes and which entrypoints reach them, the
 * topics and queues it publishes to and consumes, its {@code @Profile} beans, the config keys it reads, Quartz jobs and
 * the libraries on its classpath. Nothing here is new analysis: it is the scan's own model, written out.
 */
public final class JavaExtractor implements Extractor {
    public static final String ID = "java";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final Pattern QUOTED = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    /** Gradle's cache: .../files-2.1/group/artifact/version/hash/file.jar */
    private static final Pattern GRADLE_JAR = Pattern.compile(".*/files-2\\.1/([^/]+)/([^/]+)/([^/]+)/[^/]+/[^/]+\\.jar$");
    /** A Maven repository: .../repository/g/r/o/u/p/artifact/version/artifact-version*.jar */
    private static final Pattern MAVEN_JAR = Pattern.compile(".*/repository/(.+)/([^/]+)/([^/]+)/\\2-\\3[^/]*\\.jar$");

    private final Analysis a;
    private final ScanResult scan;
    private final List<Path> classpath;

    public JavaExtractor(Analysis a, ScanResult scan, List<Path> classpath) {
        this.a = a;
        this.scan = scan;
        this.classpath = classpath == null ? List.of() : classpath;
    }

    @Override
    public String id() {
        return ID;
    }

    /** The service's name: its {@code spring.application.name}, else the folder's. */
    public static String serviceName(Path root, ServiceSurface surface) {
        return surface.application() != null ? surface.application() : NamedFleet.serviceName(root);
    }

    @Override
    public void extract(Path root, FactSink out) {
        this.root = root;
        Sorted sink = new Sorted();
        ServiceSurface surface = ServiceSurface.of(a, scan);
        String svc = serviceName(root, surface);
        String service = "service:" + svc;
        String buildFile = buildFile(root);

        Map<String, Object> attrs = new TreeMap<>();
        if (surface.application() != null) {
            attrs.put("application", surface.application());
        }
        if (a.appPackage != null) {
            attrs.put("package", a.appPackage);
        }
        Map<String, String> libraries = libraries();
        String image = builtImage(root);
        if (image != null) {
            attrs.put("image", image);   // #161: what links the code to its deployments
        }
        String boot = libraries.get("org.springframework.boot:spring-boot");
        if (boot != null) {
            attrs.put("springBoot", boot);
        }
        sink.node(Node.of("service", svc, attrs, Evidence.at(buildFile, null)));

        Map<MethodInfo, List<String>> entryIds = entrypoints(svc, service, surface, sink);
        calls(svc, service, surface, entryIds, sink);
        beans(svc, entryIds, sink);
        tables(svc, service, entryIds, sink);
        files(svc, service, entryIds, sink);
        libraries.forEach((ga, version) -> {
            sink.node(Node.of("library", ga, Map.of("version", version), Evidence.at(buildFile, null)));
            sink.edge(Edge.extracted(service, "depends-on", "library:" + ga, Map.of("version", version), Evidence.at(buildFile, null)));
        });
        sink.flush(out);
    }

    // ---- entrypoints --------------------------------------------------------------------------------------------------

    private Map<MethodInfo, List<String>> entrypoints(String svc, String service, ServiceSurface surface, Sorted sink) {
        Map<MethodInfo, List<String>> ids = new LinkedHashMap<>();
        Map<String, List<ServiceSurface.Entry>> messaging = new LinkedHashMap<>();
        for (ServiceSurface.Entry e : surface.entries()) {
            if (!e.kind().equals("http")) {
                messaging.computeIfAbsent(e.file() + ":" + e.line(), k -> new ArrayList<>()).add(e);
            }
        }
        for (Entrypoint ep : a.entrypoints) {
            MethodInfo m = ep.method();
            Evidence at = Evidence.at(m.file().relPath(), line(m.line()));
            String kind = ep.kind().name().toLowerCase(Locale.ROOT);
            List<String> mine = ids.computeIfAbsent(m, k -> new ArrayList<>());
            switch (ep.kind()) {
                case HTTP -> {
                    for (String label : ep.labels()) {
                        String[] vp = label.split(" ", 2);
                        Map<String, Object> at2 = base(kind, m, ep);
                        if (vp.length == 2) {
                            at2.put("method", vp[0]);
                            at2.put("path", vp[1]);
                        } else {
                            at2.put("path", label);
                        }
                        Node n = Node.of("endpoint", svc + ":" + label, at2, at);
                        sink.node(n);
                        sink.edge(Edge.extracted(service, "has", n.id(), null, at));
                        mine.add(n.id());
                    }
                }
                case KAFKA, RABBIT, JMS -> {
                    List<ServiceSurface.Entry> dests = messaging.getOrDefault(m.file().relPath() + ":" + m.line(), List.of());
                    for (ServiceSurface.Entry e : dests) {
                        Map<String, Object> at2 = base(kind, m, ep);
                        at2.put("destination", e.target());
                        Node n = Node.of("listener", svc + ":" + kind + " " + e.target(), at2, at);
                        sink.node(n);
                        sink.edge(Edge.extracted(service, "has", n.id(), null, at));
                        sink.edge(Edge.extracted(n.id(), "consumes", destination(kind, e.target()), null, at));
                        mine.add(n.id());
                    }
                    if (dests.isEmpty()) {   // a destination logast could not read: the listener still exists
                        Node n = Node.of("listener", svc + ":" + kind + " " + m.label(), base(kind, m, ep), at);
                        sink.node(n);
                        sink.edge(Edge.extracted(service, "has", n.id(), null, at));
                        mine.add(n.id());
                    }
                }
                case SCHEDULED, BATCH, STEP, ITEM, MAIN -> {
                    Map<String, Object> at2 = base(kind, m, ep);
                    schedule(m, at2);
                    Node n = Node.of("job", svc + ":" + m.label(), at2, at);
                    sink.node(n);
                    sink.edge(Edge.extracted(service, "has", n.id(), null, at));
                    mine.add(n.id());
                }
                default -> {
                    Node n = Node.of("listener", svc + ":" + kind + " " + ep.label(), base(kind, m, ep), at);
                    sink.node(n);
                    sink.edge(Edge.extracted(service, "has", n.id(), null, at));
                    mine.add(n.id());
                }
            }
        }
        quartz(svc, service, sink, ids);
        return ids;
    }

    private static Map<String, Object> base(String kind, MethodInfo m, Entrypoint ep) {
        Map<String, Object> attrs = new TreeMap<>();
        attrs.put("entrypoint", kind);
        attrs.put("handler", m.owner().fqn() + "." + m.name());
        attrs.put("observed", ep.observedByFramework());
        return attrs;
    }

    /** {@code @Scheduled}'s trigger as written: the cron (placeholders kept: each environment resolves them), rate, delay, zone. */
    private void schedule(MethodInfo m, Map<String, Object> attrs) {
        for (J.Annotation an : m.decl().getLeadingAnnotations()) {
            if (Annotations.is(an, "org.springframework.scheduling.annotation.Scheduled")) {
                for (String name : List.of("cron", "fixedRate", "fixedDelay", "fixedRateString", "fixedDelayString", "initialDelay", "zone")) {
                    Annotations.rawAttribute(an, name).ifPresent(v -> attrs.put(name.replace("String", ""), unquote(v)));
                }
            }
        }
        for (J.Annotation an : m.decl().getLeadingAnnotations()) {   // #178: the unit fixedRate and fixedDelay are in
            if (Annotations.is(an, "org.springframework.scheduling.annotation.Scheduled")) {
                Annotations.rawAttribute(an, "timeUnit").ifPresent(v -> attrs.put("timeUnit", v.substring(v.lastIndexOf('.') + 1)));
            }
        }
        for (String key : List.of("fixedRate", "fixedDelay", "initialDelay")) {   // #178: placeholders resolved, as for cron
            if (attrs.get(key) instanceof String v && v.contains("${")) {
                String r = resolve(v);
                if (r != null) {
                    attrs.put(key + "Resolved", r);
                }
            }
        }
        // #159: a ${…} in the cron from the service's base config, else the placeholder's default; as written stays in cron
        Object cron = attrs.get("cron");
        if (cron instanceof String c && c.contains("${")) {
            Matcher pm = PLACEHOLDER.matcher(c);
            StringBuilder sb = new StringBuilder();
            boolean resolved = true;
            while (pm.find()) {
                String v = a.springProperties.get(pm.group(1).strip());
                if (v == null) {
                    v = pm.group(2);
                }
                if (v == null || v.contains("${")) {
                    resolved = false;
                    break;
                }
                pm.appendReplacement(sb, Matcher.quoteReplacement(v));
            }
            if (resolved) {
                pm.appendTail(sb);
                attrs.put("cronResolved", sb.toString());
            }
        }
    }

    /** Placeholders from the base config, else their default; null when one has neither. */
    private String resolve(String text) {
        Matcher pm = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (pm.find()) {
            String v = a.springProperties.get(pm.group(1).strip());
            if (v == null) {
                v = pm.group(2);
            }
            if (v == null || v.contains("${")) {
                return null;
            }
            pm.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        pm.appendTail(sb);
        return sb.toString();
    }

    private static String destination(String kind, String target) {
        return (kind.equals("kafka") ? "topic:" : "queue:") + target;
    }

    // ---- calls --------------------------------------------------------------------------------------------------------

    private void calls(String svc, String service, ServiceSurface surface, Map<MethodInfo, List<String>> entryIds, Sorted sink) {
        Map<String, MethodInfo> byLabel = new LinkedHashMap<>();
        a.index.methods().forEach(m -> byLabel.putIfAbsent(m.file().relPath() + "#" + m.label(), m));
        for (ServiceSurface.Call c : surface.calls()) {
            Evidence at = Evidence.at(c.file(), line(c.line()));
            String target = (c.verb() == null ? "" : c.verb() + " ") + (c.base() == null ? "" : c.base()) + c.target();
            Map<String, Object> attrs = new TreeMap<>();
            attrs.put("transport", c.kind());
            attrs.put("target", c.target());
            attrs.put("client", c.client());
            if (c.verb() != null) {
                attrs.put("method", c.verb());
            }
            if (c.base() != null) {
                attrs.put("base", c.base());
            }
            Node call = Node.of("call", svc + ":" + c.kind() + " " + target, attrs, at);
            sink.node(call);
            if (!c.kind().equals("http")) {
                sink.edge(Edge.extracted(call.id(), "publishes", destination(c.kind(), c.target()), null, at));
            }
            Map<String, Object> edgeAttrs = new TreeMap<>();
            if (c.breaks() != null) {
                edgeAttrs.put("traceBreaks", c.breaks().what());
            }
            MethodInfo m = byLabel.get(c.file() + "#" + c.method());
            Set<String> from = new LinkedHashSet<>();
            if (m != null) {
                from.addAll(entryIds.getOrDefault(m, List.of()));
                for (Entrypoint ep : a.paths.entrypointsReaching(m)) {
                    from.addAll(entryIds.getOrDefault(ep.method(), List.of()));
                }
            }
            if (from.isEmpty()) {   // no entrypoint reaches it (startup code, an unresolved caller): the service makes it
                Map<String, Object> e2 = new TreeMap<>(edgeAttrs);
                e2.put("reached", false);
                sink.edge(Edge.extracted(service, "calls", call.id(), e2, at));
            }
            for (String f : from) {
                sink.edge(Edge.extracted(f, "calls", call.id(), edgeAttrs, at));
            }
        }
    }

    // ---- beans: @Profile, config keys ---------------------------------------------------------------------------------

    private void beans(String svc, Map<MethodInfo, List<String>> entryIds, Sorted sink) {
        for (ClassInfo c : a.index.classes()) {
            String bean = "bean:" + svc + ":" + c.fqn();
            String file = c.file().relPath();
            List<String> profiles = new ArrayList<>();
            int profileLine = -1;
            for (J.Annotation an : c.decl().getLeadingAnnotations()) {
                if (Annotations.is(an, "org.springframework.context.annotation.Profile")) {
                    profiles.addAll(strings(an));
                    profileLine = Pos.line(an);
                }
            }
            List<J.Annotation> values = new ArrayList<>();
            List<J.Annotation> configProps = new ArrayList<>();
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, Integer p) {
                    return cd == c.decl() ? super.visitClassDeclaration(cd, p) : cd;   // nested classes are their own beans
                }

                @Override
                public J.Annotation visitAnnotation(J.Annotation an, Integer p) {
                    if (Annotations.is(an, "org.springframework.beans.factory.annotation.Value")) {
                        values.add(an);
                    } else if (Annotations.is(an, "org.springframework.boot.context.properties.ConfigurationProperties")) {
                        configProps.add(an);
                    }
                    return an;
                }
            }.visit(c.decl(), 0);
            if (profiles.isEmpty() && values.isEmpty() && configProps.isEmpty()) {
                continue;
            }
            Map<String, Object> attrs = new TreeMap<>();
            attrs.put("class", c.fqn());
            if (!profiles.isEmpty()) {
                attrs.put("profiles", profiles);
            }
            Evidence classAt = Evidence.at(file, line(Pos.line(c.decl().getName())));
            sink.node(Node.of("bean", svc + ":" + c.fqn(), attrs, classAt));
            for (String p : profiles) {
                Map<String, Object> pa = new TreeMap<>();
                String name = p.strip();
                if (name.startsWith("!")) {   // active when that profile is not
                    pa.put("negated", true);
                    name = name.substring(1).strip();
                }
                sink.edge(Edge.extracted(bean, "active-with", "profile:" + svc + ":" + name, pa, Evidence.at(file, line(profileLine))));
            }
            if (!profiles.isEmpty()) {   // which entrypoints run this profile's bean: profile → bean → the code that runs
                Set<String> runs = new LinkedHashSet<>();
                for (MethodInfo m : a.index.methodsOf(c.fqn())) {
                    runs.addAll(entryIds.getOrDefault(m, List.of()));
                    for (Entrypoint ep : a.paths.entrypointsReaching(m)) {
                        runs.addAll(entryIds.getOrDefault(ep.method(), List.of()));
                    }
                }
                runs.forEach(r -> sink.edge(Edge.extracted(r, "runs", bean, null, classAt)));
            }
            for (J.Annotation an : values) {
                String raw = Annotations.rawAttribute(an, "value").map(JavaExtractor::unquote).orElse("");
                Matcher m = PLACEHOLDER.matcher(raw);
                while (m.find()) {
                    Map<String, Object> pa = new TreeMap<>();
                    if (m.group(2) != null) {
                        pa.put("default", dev.logast.facts.Secrets.clean(m.group(1), m.group(2)));   // #183
                    }
                    sink.edge(Edge.extracted(bean, "uses-property", "property:" + svc + ":" + m.group(1).strip(), pa,
                            Evidence.at(file, line(Pos.line(an)))));
                }
            }
            for (J.Annotation an : configProps) {
                String prefix = Annotations.stringAttribute(an, "prefix", "value").orElse("");
                if (!prefix.isBlank()) {
                    sink.edge(Edge.extracted(bean, "uses-property", "property:" + svc + ":" + prefix + ".*",
                            Map.of("prefix", true), Evidence.at(file, line(Pos.line(an)))));
                }
            }
        }
    }

    // ---- tables (#154) ----------------------------------------------------------------------------------------------

    private void tables(String svc, String service, Map<MethodInfo, List<String>> entryIds, Sorted sink) {
        DataAccess data = new DataAccess(a.index);
        for (DataAccess.Entity e : data.entities()) {
            ClassInfo c = e.clazz();
            Evidence at = Evidence.at(c.file().relPath(), line(Pos.line(c.decl().getName())));
            Node n = Node.of("entity", svc + ":" + c.fqn(), Map.of("name", e.name(), "table", e.table()), at);
            sink.node(n);
            sink.edge(Edge.extracted(n.id(), "maps", "table:" + e.table(), null, at));
        }
        for (DataAccess.Access x : data.accesses()) {
            Evidence at = Evidence.at(x.file(), line(x.line()));
            Map<String, Object> attrs = new TreeMap<>();
            attrs.put("via", x.via());
            if (!x.parsed() && x.table() != null) {
                attrs.put("parsed", false);   // read from the statement's keywords: JSqlParser could not parse it
            }
            String kind;
            String to;
            if (x.table() != null) {
                kind = x.write() ? "writes" : "reads";
                to = "table:" + x.table();
            } else {   // SQL built at runtime: where it runs is known, not which table
                kind = "uses-sql";
                Node sql = Node.of("sql", svc + ":" + x.file() + ":" + x.line(), Map.of("via", x.via(), "resolved", false), at);
                sink.node(sql);
                to = sql.id();
            }
            Set<String> from = new LinkedHashSet<>(entryIds.getOrDefault(x.method(), List.of()));
            for (Entrypoint ep : a.paths.entrypointsReaching(x.method())) {
                from.addAll(entryIds.getOrDefault(ep.method(), List.of()));
            }
            if (from.isEmpty()) {
                attrs.put("reached", false);
                sink.edge(Edge.extracted(service, kind, to, attrs, at));
            }
            for (String f : from) {
                sink.edge(Edge.extracted(f, kind, to, attrs, at));
            }
        }
    }

    // ---- files (#172) -----------------------------------------------------------------------------------------------

    private void files(String svc, String service, Map<MethodInfo, List<String>> entryIds, Sorted sink) {
        for (FileAccess.Access x : new FileAccess(a.index, a.springProperties).accesses()) {
            Evidence at = Evidence.at(x.file(), line(x.line()));
            Map<String, Object> attrs = new TreeMap<>();
            attrs.put("via", x.via());
            String to = "file-location:" + x.location();
            String kind = x.write() ? "writes" : "reads";
            Set<String> from = new LinkedHashSet<>(entryIds.getOrDefault(x.method(), List.of()));
            for (Entrypoint ep : a.paths.entrypointsReaching(x.method())) {
                from.addAll(entryIds.getOrDefault(ep.method(), List.of()));
            }
            if (from.isEmpty()) {
                attrs.put("reached", false);
                sink.edge(Edge.extracted(service, kind, to, attrs, at));
            }
            for (String f : from) {
                sink.edge(Edge.extracted(f, kind, to, attrs, at));
            }
        }
    }

    // ---- Quartz -------------------------------------------------------------------------------------------------------

    private Path root;   // the service's folder, while extracting

    private static final Set<String> QUARTZ_TYPES = Set.of("org.quartz.Job", "org.quartz.InterruptableJob", "org.quartz.StatefulJob",
            "org.springframework.scheduling.quartz.QuartzJobBean");
    private static final Pattern CLASS_LITERAL = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\.class\\b");
    private static final Pattern CRON_CALL = Pattern.compile("(?:cronSchedule|cronScheduleNonvalidatedExpression|setCronExpression|"
            + "withCronSchedule|cronExpression|new\\s+CronExpression)\\s*\\(\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([A-Za-z_][A-Za-z0-9_.]*))\\s*\\)");

    /**
     * #187: Quartz jobs: classes that are a {@code org.quartz.Job} (or Spring's {@code QuartzJobBean}), by type when the
     * classpath has Quartz, else by what the source names (with an import of Quartz), and classes extending such a class
     * of the repo. Each is an entrypoint: its {@code execute} (or {@code executeInternal}; else the methods it overrides
     * of a base class) is where its calls, reads and writes start. Its schedule, when the code builds it next to the
     * job's class ({@code JobBuilder.newJob(X.class)} … {@code cronSchedule("…")}, a {@code CronTriggerFactoryBean}),
     * is its {@code cron}, in Quartz's dialect.
     */
    private void quartz(String svc, String service, Sorted sink, Map<MethodInfo, List<String>> ids) {
        Map<String, ClassInfo> jobs = quartzJobs();
        if (jobs.isEmpty()) {
            return;
        }
        Map<String, String> crons = quartzSchedules(jobs);
        enumJobs = enumJobs(jobs);   // #190: enum constants that carry a job's metadata
        Map<String, Map<String, String>> fromConfig = configSchedules(jobs);   // #189: application.yml, per profile
        for (ClassInfo c : jobs.values()) {
            if (c.decl().hasModifier(J.Modifier.Type.Abstract)) {
                continue;   // a base class: its subclasses are the jobs
            }
            List<MethodInfo> own = a.index.methods().stream().filter(m -> m.owner().fqn().equals(c.fqn())).toList();
            List<MethodInfo> entry = own.stream().filter(m -> m.name().equals("execute") || m.name().equals("executeInternal")).toList();
            if (entry.isEmpty()) {
                entry = own.stream().filter(m -> m.decl().getLeadingAnnotations().stream()
                        .anyMatch(an -> an.getSimpleName().equals("Override"))).toList();
            }
            Evidence at = Evidence.at(c.file().relPath(), line(Pos.line(c.decl().getName())));
            Map<String, Object> attrs = new TreeMap<>();
            attrs.put("entrypoint", "quartz");
            attrs.put("handler", c.fqn());
            String cron = crons.get(c.fqn());
            EnumJob ej = enumJobs.values().stream().filter(e -> e.job().equals(c.fqn())).findFirst().orElse(null);
            if (ej != null) {
                attrs.put("enumConstant", ej.constant());
                if (cron == null && ej.cron() != null) {
                    cron = ej.cron();
                    attrs.put("cronFrom", "enum " + ej.constant());
                }
            }
            Map<String, String> configured = fromConfig.getOrDefault(c.fqn(), Map.of());
            if (configured.containsKey("") && (cron == null || ej != null && cron.equals(ej.cron()))) {   // the configuration wins over the enum
                cron = configured.get("");
                attrs.put("cronFrom", configured.get("@from"));
            }
            Map<String, String> byProfile = new TreeMap<>();
            configured.forEach((p, v) -> {
                if (!p.isEmpty() && !p.startsWith("@")) {
                    byProfile.put(p, v);
                }
            });
            List<Map<String, String>> triggers = configTriggers.getOrDefault(c.fqn(), List.of());
            if (triggers.size() > 1) {   // #197: a job with several triggers runs at each
                attrs.put("triggers", triggers);
            }
            if (!byProfile.isEmpty()) {
                attrs.put("cronByProfile", byProfile);
                attrs.putIfAbsent("cronFrom", configured.get("@from"));
            }
            if (cron != null) {
                attrs.put("cron", cron);
                if (!entry.isEmpty()) {
                    schedule(entry.getFirst(), attrs);   // resolves a ${…} in it, as for @Scheduled
                }
            }
            Node n = Node.of("job", svc + ":" + c.fqn(), attrs, at);
            sink.node(n);
            sink.edge(Edge.extracted(service, "has", n.id(), null, at));
            for (MethodInfo m : entry) {
                ids.computeIfAbsent(m, k -> new ArrayList<>()).add(n.id());
            }
        }
    }

    /** fqn → class, for every Quartz job class of the repo (abstract ones too: their subclasses are found through them). */
    Map<String, ClassInfo> quartzJobs() {
        Map<String, ClassInfo> jobs = new LinkedHashMap<>();
        List<ClassInfo> classes = a.index.classes().stream().filter(c -> !c.isInterface()).toList();
        for (ClassInfo c : classes) {
            boolean byType = c.type() != null && QUARTZ_TYPES.stream().anyMatch(t -> TypeUtils.isAssignableTo(t, c.type()));
            boolean bySource = (c.file().text().contains("org.quartz.") || c.file().text().contains("org.springframework.scheduling.quartz"))
                    && supertypes(c).stream().anyMatch(n -> QUARTZ_TYPES.contains(n)
                    || QUARTZ_TYPES.stream().anyMatch(t -> t.endsWith("." + n)));
            if (byType || bySource) {
                jobs.put(c.fqn(), c);
            }
        }
        boolean more = true;
        while (more) {   // a class extending a job class of the repo, without a classpath to say so
            more = false;
            for (ClassInfo c : classes) {
                if (jobs.containsKey(c.fqn())) {
                    continue;
                }
                for (String n : supertypes(c)) {
                    ClassInfo base = jobs.get(n);
                    if (base == null) {
                        base = jobs.values().stream().filter(j -> j.simpleName().equals(n)).findFirst().orElse(null);
                    }
                    if (base != null) {
                        jobs.put(c.fqn(), c);
                        more = true;
                        break;
                    }
                }
            }
        }
        return jobs;
    }

    /** The names a class extends and implements, as written (generics dropped) and, when resolved, fully qualified. */
    private static List<String> supertypes(ClassInfo c) {
        List<org.openrewrite.java.tree.TypeTree> trees = new ArrayList<>();
        if (c.decl().getExtends() != null) {
            trees.add(c.decl().getExtends());
        }
        if (c.decl().getImplements() != null) {
            trees.addAll(c.decl().getImplements());
        }
        List<String> out = new ArrayList<>();
        for (var t : trees) {
            out.add(t.printTrimmed().replaceAll("<.*|\\s+", ""));
            if (t.getType() instanceof org.openrewrite.java.tree.JavaType.FullyQualified fq && !(fq instanceof org.openrewrite.java.tree.JavaType.Unknown)) {
                out.add(fq.getFullyQualifiedName());
            }
        }
        return out;
    }

    /**
     * #187: job class → its cron, where the code builds the schedule next to the class: in one method (one job class
     * literal and a cron), else in one class (a configuration with one job and its trigger). A cron in a constant is
     * read from the constant; ambiguous places (two jobs, two crons) give none.
     */
    private Map<String, String> quartzSchedules(Map<String, ClassInfo> jobs) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, List<String[]>> perClass = new LinkedHashMap<>();   // class → [job, cron] found in its methods
        for (MethodInfo m : a.index.methods()) {
            String text = m.decl().getBody() == null ? "" : m.decl().getBody().printTrimmed();
            if (!text.contains(".class")) {
                continue;
            }
            java.util.Set<String> js = new java.util.LinkedHashSet<>();
            Matcher cl = CLASS_LITERAL.matcher(text);
            while (cl.find()) {
                String n = cl.group(1);
                jobs.values().stream().filter(j -> j.simpleName().equals(n) && !j.decl().hasModifier(J.Modifier.Type.Abstract))
                        .findFirst().ifPresent(j -> js.add(j.fqn()));
            }
            java.util.Set<String> cs = new java.util.LinkedHashSet<>();
            Matcher cr = CRON_CALL.matcher(text);
            while (cr.find()) {
                String v = cr.group(1) != null ? cr.group(1) : constant(m.owner(), cr.group(2));
                if (v != null) {
                    cs.add(v);
                }
            }
            List<String[]> found = perClass.computeIfAbsent(m.owner().fqn(), k -> new ArrayList<>());
            js.forEach(j -> found.add(new String[]{j, null}));
            cs.forEach(c -> found.add(new String[]{null, c}));
            if (js.size() == 1 && cs.size() == 1) {
                out.putIfAbsent(js.iterator().next(), cs.iterator().next());
            }
        }
        perClass.forEach((cls, found) -> {
            List<String> js = found.stream().map(f -> f[0]).filter(java.util.Objects::nonNull).distinct().toList();
            List<String> cs = found.stream().map(f -> f[1]).filter(java.util.Objects::nonNull).distinct().toList();
            if (js.size() == 1 && cs.size() == 1) {
                out.putIfAbsent(js.getFirst(), cs.getFirst());
            }
        });
        return out;
    }

    private static final Pattern CRON_SHAPE = Pattern.compile("^[0-9*?/,A-Za-z#-]+(\\s+[0-9*?/,A-Za-z#-]+){4,6}$");

    /**
     * #189: schedules a service's configuration binds to its jobs ({@code @ConfigurationProperties} over
     * application.yml): an entry (a list item or a map entry) with a value naming the job (its class, simple name or
     * bean name, or the entry's own key) and a cron (a key containing "cron", or a value shaped like one). Job → profile
     * ("" for the base files) → cron, and "@from" → the key it came from; a profile's cron only where it differs.
     */
    private Map<String, Map<String, String>> configSchedules(Map<String, ClassInfo> jobs) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (root == null) {
            return out;
        }
        Map<String, String> base = dev.logast.SpringProperties.read(root);
        configSchedules(jobs, base, "", out, null);
        for (String profile : profiles(root)) {
            configSchedules(jobs, dev.logast.SpringProperties.forProfile(root, profile), profile, out, base);
        }
        return out;
    }

    private static List<String> profiles(Path root) {
        List<String> out = new ArrayList<>();
        Path resources = dev.logast.SpringProperties.appResources(root);
        if (resources == null) {
            return out;
        }
        for (Path dir : List.of(resources, resources.resolve("config"))) {
            if (Files.isDirectory(dir)) {
                try (var s = Files.list(dir)) {
                    s.map(p -> p.getFileName().toString()).map(n -> n.matches("application-([^.]+)\\.(yml|yaml|properties)")
                            ? n.replaceAll("application-([^.]+)\\..*", "$1") : null).filter(java.util.Objects::nonNull)
                            .sorted().distinct().forEach(out::add);
                } catch (java.io.IOException e) {
                    // unreadable: no profiles
                }
            }
        }
        return out.stream().distinct().toList();
    }

    private void configSchedules(Map<String, ClassInfo> jobs, Map<String, String> props, String profile,
                                        Map<String, Map<String, String>> out, Map<String, String> base) {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();   // entry → its keys
        props.forEach((k, v) -> {
            int dot = k.lastIndexOf('.');
            if (dot > 0) {
                groups.computeIfAbsent(k.substring(0, dot), x -> new LinkedHashMap<>()).put(k.substring(dot + 1), v);
            }
        });
        groups.forEach((entry, keys) -> {
            String cronKey = keys.keySet().stream().filter(k -> k.toLowerCase(Locale.ROOT).contains("cron")
                    && CRON_SHAPE.matcher(keys.get(k).strip()).matches()).findFirst()
                    .orElse(keys.keySet().stream().filter(k -> CRON_SHAPE.matcher(keys.get(k).strip()).matches()
                            && keys.get(k).matches(".*[0-9*?].*")).findFirst().orElse(null));
            if (cronKey == null) {
                return;
            }
            // the job: named in the entry itself, else in the entry it is part of (#197: jobs.<key>.triggers[i].cron
            // under jobs.<key>.name), at most three levels up
            ClassInfo job = null;
            String at = entry;
            for (int up = 0; up <= 3 && job == null && at != null; up++) {
                Map<String, String> own = groups.getOrDefault(at, Map.of());
                List<String> names = new ArrayList<>(own.values());
                names.add(at.substring(at.lastIndexOf('.') + 1).replaceAll("\\[\\d+]$", ""));
                for (String n : names) {
                    job = jobNamed(jobs, n);
                    if (job != null) {
                        break;
                    }
                }
                at = parentKey(at);
            }
            if (job == null) {
                return;
            }
            String key = entry + "." + cronKey;
            String cron = keys.get(cronKey).strip();
            if (base == null) {   // #197: every trigger of the job, with what else its entry says (its name, a country)
                Map<String, String> trigger = new TreeMap<>();
                keys.forEach((k, v) -> {
                    if (v != null && v.length() <= 120) {
                        trigger.put(k.equals(cronKey) ? "cron" : k, v.strip());
                    }
                });
                trigger.put("from", key);
                List<Map<String, String>> list = configTriggers.computeIfAbsent(job.fqn(), x -> new ArrayList<>());
                if (list.stream().noneMatch(t -> t.get("from").equals(key))) {
                    list.add(trigger);
                }
            }
            if (base != null && cron.equals(base.get(key))) {
                return;   // as in the base files
            }
            Map<String, String> m = out.computeIfAbsent(job.fqn(), x -> new TreeMap<>());
            m.putIfAbsent(profile, cron);
            m.putIfAbsent("@from", "application config: " + key);
        });
    }

    /** #190: an enum constant that carries a job's metadata: the job it stands for, and a cron among its arguments. */
    record EnumJob(String constant, String job, String cron) {
    }

    private Map<String, EnumJob> enumJobs = Map.of();   // normalized constant name → its job

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * #190: enum constants naming a job: a constant whose arguments name it ({@code CLEANUP(CleanupJob.class, …)} or the
     * job's name), or the one constant of an enum that a job's class refers to ({@code JobType.CLEANUP} in CleanupJob).
     * Keyed by the constant's name without case and separators (Spring binds {@code CLEANUP}, {@code cleanup} and
     * {@code clean-up} alike); a name two jobs claim is left out.
     */
    private Map<String, EnumJob> enumJobs(Map<String, ClassInfo> jobs) {
        Map<String, EnumJob> out = new LinkedHashMap<>();
        java.util.Set<String> clashes = new java.util.HashSet<>();
        java.util.function.Consumer<EnumJob> add = e -> {
            String k = norm(e.constant().substring(e.constant().lastIndexOf('.') + 1));
            EnumJob was = out.putIfAbsent(k, e);
            if (was != null && !was.job().equals(e.job())) {
                clashes.add(k);
            }
        };
        List<ClassInfo> enums = a.index.classes().stream().filter(c -> c.decl().getKind() == J.ClassDeclaration.Kind.Type.Enum).toList();
        for (ClassInfo en : enums) {
            if (en.decl().getBody() == null) {
                continue;
            }
            for (var st : en.decl().getBody().getStatements()) {
                if (!(st instanceof J.EnumValueSet set)) {
                    continue;
                }
                for (J.EnumValue v : set.getEnums()) {
                    String text = v.printTrimmed();
                    ClassInfo job = null;
                    Matcher cl = CLASS_LITERAL.matcher(text);
                    while (job == null && cl.find()) {
                        job = jobNamed(jobs, cl.group(1));
                    }
                    String cron = null;
                    Matcher lit = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
                    while (lit.find()) {
                        String l = lit.group(1);
                        if (job == null) {
                            job = jobNamed(jobs, l);
                        }
                        if (cron == null && CRON_SHAPE.matcher(l.strip()).matches() && l.matches(".*[0-9*?].*")) {
                            cron = l.strip();
                        }
                    }
                    if (job != null) {
                        add.accept(new EnumJob(en.simpleName() + "." + v.getName().getSimpleName(), job.fqn(), cron));
                    }
                }
            }
        }
        for (ClassInfo job : jobs.values()) {   // a job's class that refers to one constant of an enum of the repo
            if (job.decl().hasModifier(J.Modifier.Type.Abstract)) {
                continue;
            }
            String text = job.decl().printTrimmed();
            for (ClassInfo en : enums) {
                Matcher m = Pattern.compile("\\b" + Pattern.quote(en.simpleName()) + "\\.([A-Z][A-Z0-9_]*)\\b").matcher(text);
                java.util.Set<String> refs = new java.util.LinkedHashSet<>();
                while (m.find()) {
                    refs.add(m.group(1));
                }
                if (refs.size() == 1) {
                    String constant = en.simpleName() + "." + refs.iterator().next();
                    if (out.values().stream().noneMatch(e -> e.constant().equals(constant))) {
                        add.accept(new EnumJob(constant, job.fqn(), null));
                    }
                }
            }
        }
        clashes.forEach(out::remove);
        return out;
    }

    /** #197: job → the triggers its configuration lists (base files). */
    private final Map<String, List<Map<String, String>>> configTriggers = new LinkedHashMap<>();

    /** The entry a key is part of: {@code a.b[2]} → {@code a.b}, {@code a.b.c} → {@code a.b}; null at the top. */
    private static String parentKey(String key) {
        if (key.endsWith("]")) {
            int i = key.lastIndexOf('[');
            return i > 0 ? parentKey2(key.substring(0, i)) : null;
        }
        return parentKey2(key);
    }

    private static String parentKey2(String key) {
        int dot = key.lastIndexOf('.');
        return dot > 0 ? key.substring(0, dot) : null;
    }

    /** The job a configuration value names: its class, simple name, bean name, a key like {@code cleanup-job}, or an enum constant. */
    private ClassInfo jobNamed(Map<String, ClassInfo> jobs, String value) {
        if (value == null || value.isBlank() || value.length() > 200) {
            return null;
        }
        String v = value.strip();
        String norm = norm(v);
        EnumJob ej = enumJobs.get(norm);
        if (ej != null && jobs.containsKey(ej.job())) {
            return jobs.get(ej.job());
        }
        for (ClassInfo c : jobs.values()) {
            if (c.decl().hasModifier(J.Modifier.Type.Abstract)) {
                continue;
            }
            String simple = c.simpleName().toLowerCase(Locale.ROOT);
            if (v.equals(c.fqn()) || norm.equals(simple) || (simple.endsWith("job") && simple.length() > 3
                    && norm.equals(simple.substring(0, simple.length() - 3)))) {
                return c;
            }
        }
        return null;
    }

    /** A string constant's value: {@code NAME = "…"} in the class's file (or {@code Other.NAME} in the repo). */
    private String constant(ClassInfo owner, String ref) {
        String name = ref.substring(ref.lastIndexOf('.') + 1);
        Pattern def = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher mm = def.matcher(owner.file().text());
        if (mm.find()) {
            return mm.group(1);
        }
        if (ref.contains(".")) {
            String cls = ref.substring(0, ref.lastIndexOf('.'));
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            for (ClassInfo c : a.index.classes()) {
                if (c.simpleName().equals(simple)) {
                    Matcher m2 = def.matcher(c.file().text());
                    if (m2.find()) {
                        return m2.group(1);
                    }
                }
            }
        }
        return null;
    }

    // ---- libraries ----------------------------------------------------------------------------------------------------

    /** group:artifact → version, from the jar paths of the classpath (Gradle's cache or a Maven repository). */
    Map<String, String> libraries() {
        Map<String, String> out = new TreeMap<>();
        for (Path p : classpath) {
            String s = p.toString().replace('\\', '/');
            Matcher g = GRADLE_JAR.matcher(s);
            if (g.matches()) {
                out.putIfAbsent(g.group(1) + ":" + g.group(2), g.group(3));
                continue;
            }
            Matcher mv = MAVEN_JAR.matcher(s);
            if (mv.matches()) {
                out.putIfAbsent(mv.group(1).replace('/', '.') + ":" + mv.group(2), mv.group(3));
            }
        }
        return out;
    }

    private static final Pattern JIB_IMAGE = Pattern.compile("(?s)\\bto\\s*\\{[^}]*?\\bimage\\s*(?:=|\\.set\\()\\s*[\"']([^\"']+)[\"']"
            + "|jib\\.to\\.image\\s*(?:=|\\.set\\()\\s*[\"']([^\"']+)[\"']");
    private static final Pattern BOOT_IMAGE = Pattern.compile("(?s)bootBuildImage\\s*(?:\\{|\\([^)]*\\)\\s*\\{)[^}]*?\\bimageName\\s*(?:=|\\.set\\()\\s*[\"']([^\"']+)[\"']");

    /** #161: the image the build pushes, when it says so (Jib's {@code to.image}, {@code bootBuildImage}'s imageName). */
    public static String builtImage(Path root) {
        for (String f : List.of("build.gradle.kts", "build.gradle")) {
            Path p = root.resolve(f);
            if (!Files.isRegularFile(p)) {
                continue;
            }
            String text;
            try {
                text = Files.readString(p);
            } catch (java.io.IOException e) {
                return null;
            }
            for (Pattern pt : List.of(JIB_IMAGE, BOOT_IMAGE)) {
                Matcher m = pt.matcher(text);
                if (m.find()) {
                    String image = m.group(1) != null ? m.group(1) : m.groupCount() >= 2 ? m.group(2) : null;
                    return image == null || image.contains("$") ? null : image;
                }
            }
        }
        return null;
    }

    private static String buildFile(Path root) {
        for (String f : List.of("build.gradle.kts", "build.gradle", "pom.xml")) {
            if (Files.isRegularFile(root.resolve(f))) {
                return f;
            }
        }
        return "build.gradle.kts";
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private static Integer line(int line) {
        return line > 0 ? line : null;
    }

    private static List<String> strings(J.Annotation an) {
        List<String> out = new ArrayList<>();
        Annotations.rawAttribute(an, "value").ifPresent(raw -> {
            Matcher m = QUOTED.matcher(raw);
            while (m.find()) {
                out.add(m.group(1));
            }
        });
        return out;
    }

    private static String unquote(String raw) {
        String s = raw.strip();
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    /** Collects facts, drops repeats and writes them sorted: the same code always gives the same file. */
    private static final class Sorted implements FactSink {
        private final Map<String, Node> nodes = new TreeMap<>();
        private final Map<String, Edge> edges = new TreeMap<>();

        @Override
        public void node(Node n) {
            nodes.putIfAbsent(n.id() + "\u0000" + n.env(), n);
        }

        @Override
        public void edge(Edge e) {
            String key = e.from() + "\u0000" + e.kind() + "\u0000" + e.to() + "\u0000" + e.env() + "\u0000"
                    + (e.evidence() == null ? "" : e.evidence().file() + ":" + e.evidence().line());
            edges.putIfAbsent(key, e);
        }

        void flush(FactSink out) {
            nodes.values().forEach(out::node);
            edges.values().forEach(out::edge);
        }
    }
}
