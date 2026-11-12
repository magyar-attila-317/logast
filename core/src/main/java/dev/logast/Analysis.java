package dev.logast;

import dev.logast.graph.*;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.parse.ParsedProject;
import dev.logast.parse.ProjectParser;
import dev.logast.policy.Policy;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.file.Path;
import java.util.*;

/** Everything the rules need: parsed sources, index, entrypoints, call graph and path context. */
public final class Analysis {
    public final ParsedProject project;
    public final Policy policy;
    public final CodeIndex index;
    public final List<Entrypoint> entrypoints;
    public final CallGraph graph;
    public final PathContext paths;
    public final Set<String> httpFilterKeys;
    public final boolean supportStarterPresent;
    /** Package of the @SpringBootApplication class, null when there is none. */
    public final String appPackage;
    /** Package generated code imports ObservedContext/MdcScope/ContextExecutors from. */
    public final String supportPackage;
    public final boolean decoratorPresent;

    /** Real entrypoint rates when provided (.logast/traffic.yaml or --traffic); never estimated. */
    public Traffic traffic = Traffic.none();

    public Analysis withTraffic(Traffic t) {
        this.traffic = t == null ? Traffic.none() : t;
        return this;
    }

    /** Flattened application.yml / application.properties of the service (base profile). */
    public final Map<String, String> springProperties;

    private Analysis(ParsedProject project, Policy policy) {
        this.project = project;
        this.policy = policy;
        this.index = CodeIndex.build(project);
        this.springProperties = SpringProperties.read(project.root());
        boolean kafkaObserved = policy.entrypoints().kafkaObservationEnabled()
                || "true".equals(springProperties.get("spring.kafka.listener.observation-enabled"));
        this.entrypoints = new EntrypointFinder(policy, kafkaObserved).find(index);
        this.graph = CallGraph.build(index);
        this.httpFilterKeys = filterKeys(index);
        this.appPackage = appPackage(index);
        this.supportPackage = policy.support().vendor()
                ? Optional.ofNullable(policy.support().vendorPackage()).orElse(appPackage == null ? "logging" : appPackage + ".logging")
                : policy.support().packageName();
        this.supportStarterPresent = policy.support().vendor()
                ? index.clazz(supportPackage + ".ObservedContext") != null
                : project.probe().hasClass(supportPackage + ".ObservedContext");
        this.decoratorPresent = supportStarterPresent || hasTaskDecorator(index);
        this.paths = new PathContext(graph, entrypoints, new TreeSet<>(policy.mdc().ambientKeys()),
                httpFilterKeys, decoratorPresent);
    }

    public static Analysis of(ParsedProject parsed, Policy policy) {
        return new Analysis(parsed, policy);
    }

    public static Analysis run(Path root, List<Path> classpath, Policy policy) {
        long t0 = System.nanoTime();
        ParsedProject parsed = new ProjectParser().parse(root, classpath);
        long t1 = System.nanoTime();
        Analysis a = new Analysis(parsed, policy);
        if (System.getenv("LOGAST_TIMING") != null) {
            System.err.printf("timing: parse %d ms, index+graph+paths %d ms%n", (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000);
        }
        return a;
    }

    private static String appPackage(CodeIndex index) {
        return index.classes().stream()
                .filter(c -> c.decl().getLeadingAnnotations().stream().anyMatch(a ->
                        dev.logast.model.Annotations.is(a, "org.springframework.boot.autoconfigure.SpringBootApplication")))
                .map(c -> c.fqn().contains(".") ? c.fqn().substring(0, c.fqn().lastIndexOf('.')) : null)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static final Set<String> PATTERN_REGISTRATION = Set.of("addUrlPatterns", "setUrlPatterns",
            "addPathPatterns", "excludePathPatterns", "addServletNames", "setServletNames");

    /**
     * MDC keys set by filters/interceptors that run on <b>every</b> request. A filter that can skip requests
     * ({@code shouldNotFilter}, URL/path patterns at registration) guarantees nothing: counting its keys would let
     * rules drop values from log messages that are not actually in MDC on the skipped paths.
     */
    private static Set<String> filterKeys(CodeIndex index) {
        Set<String> keys = new TreeSet<>();
        for (ClassInfo c : index.classes()) {
            if (TypeUtils.isAssignableTo("jakarta.servlet.Filter", c.type())
                    || TypeUtils.isAssignableTo("org.springframework.web.servlet.HandlerInterceptor", c.type())) {
                if (conditional(index, c)) {
                    continue;
                }
                for (MethodInfo m : index.methodsOf(c.fqn())) {
                    keys.addAll(PathContext.ownKeys(m));
                }
            }
        }
        return keys;
    }

    private static boolean conditional(CodeIndex index, ClassInfo filter) {
        if (index.methodsOf(filter.fqn()).stream().anyMatch(m -> m.name().equals("shouldNotFilter"))) {
            return true;
        }
        for (MethodInfo m : index.methods()) {
            boolean patterns = m.facts().calls.stream().anyMatch(cs -> PATTERN_REGISTRATION.contains(cs.type().getName()));
            if (patterns && m.decl().getBody() != null
                    && m.file().slice(dev.logast.parse.Pos.start(m.decl().getBody()), dev.logast.parse.Pos.end(m.decl().getBody()))
                    .contains(filter.simpleName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTaskDecorator(CodeIndex index) {
        String td = "org.springframework.core.task.TaskDecorator";
        return index.classes().stream().anyMatch(c -> TypeUtils.isAssignableTo(td, c.type()))
                || index.methods().stream().anyMatch(m -> TypeUtils.isAssignableTo(td, m.type().getReturnType()));
    }

    public Stats stats() {
        List<String> unresolved = new ArrayList<>();
        index.methods().forEach(m -> unresolved.addAll(m.facts().unresolved));
        long reachable = index.methods().stream().filter(paths::reachable).count();
        return new Stats(project.files().size(), index.methods().size(), entrypoints.size(), (int) reachable,
                graph.edgeCount(), graph.ambiguousSites(), unresolved.size(),
                unresolved.stream().limit(10).toList(), project.parseErrors(), project.classpath().isEmpty(),
                project.files().stream().filter(f -> !f.editable()).map(f -> f.relPath()).toList());
    }

    public record Stats(int files, int methods, int entrypoints, int reachableMethods, int edges,
                        int ambiguousCallSites, int unresolvedCalls, List<String> unresolvedSample,
                        List<String> parseErrors, boolean noClasspath, List<String> nonEditableFiles) {
    }
}
