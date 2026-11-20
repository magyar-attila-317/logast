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
        String boot = libraries.get("org.springframework.boot:spring-boot");
        if (boot != null) {
            attrs.put("springBoot", boot);
        }
        sink.node(Node.of("service", svc, attrs, Evidence.at(buildFile, null)));

        Map<MethodInfo, List<String>> entryIds = entrypoints(svc, service, surface, sink);
        calls(svc, service, surface, entryIds, sink);
        beans(svc, entryIds, sink);
        quartz(svc, service, sink);
        tables(svc, service, entryIds, sink);
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
    private static void schedule(MethodInfo m, Map<String, Object> attrs) {
        for (J.Annotation an : m.decl().getLeadingAnnotations()) {
            if (Annotations.is(an, "org.springframework.scheduling.annotation.Scheduled")) {
                for (String name : List.of("cron", "fixedRate", "fixedDelay", "fixedRateString", "fixedDelayString", "initialDelay", "zone")) {
                    Annotations.rawAttribute(an, name).ifPresent(v -> attrs.put(name.replace("String", ""), unquote(v)));
                }
            }
        }
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
                        pa.put("default", m.group(2));
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

    // ---- Quartz -------------------------------------------------------------------------------------------------------

    private void quartz(String svc, String service, Sorted sink) {
        for (ClassInfo c : a.index.classes()) {
            if (c.isInterface() || c.type() == null) {
                continue;
            }
            if (TypeUtils.isAssignableTo("org.quartz.Job", c.type())) {
                Evidence at = Evidence.at(c.file().relPath(), line(Pos.line(c.decl().getName())));
                Node n = Node.of("job", svc + ":" + c.fqn(), Map.of("entrypoint", "quartz", "handler", c.fqn()), at);
                sink.node(n);
                sink.edge(Edge.extracted(service, "has", n.id(), null, at));
            }
        }
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
