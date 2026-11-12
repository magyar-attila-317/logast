package dev.logast.rules;

import dev.logast.SpringProperties;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Tracing across process boundaries ({@code tracing} pack):
 * <ul>
 *   <li>HTTP_CLIENT_UNTRACED: clients built without Spring's instrumented builders send no traceparent;</li>
 *   <li>KAFKA_OBSERVATION_DISABLED: KafkaTemplate / @KafkaListener without observation drop the trace at the topic;</li>
 *   <li>JOB_LAUNCH_UNTRACED: an orchestrator starting a pod or process without TRACEPARENT in its environment.</li>
 * </ul>
 */
final class TracingRules {
    private static final Set<String> BEAN_ANNOTATIONS = Set.of("Component", "Service", "Repository", "Controller",
            "RestController", "Configuration");
    private static final String REST_TEMPLATE_BUILDER = "org.springframework.boot.restclient.RestTemplateBuilder";

    private final RuleContext ctx;

    TracingRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (ctx.enabled(Rules.KAFKA_OBSERVATION_DISABLED)) {
            kafka();
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            scan(m);
        }
        if (ctx.enabled(Rules.HTTP_CLIENT_UNTRACED)) {
            fieldInitializers();
            feign();
        }
        if (ctx.enabled(Rules.TRACING_SAMPLING) && ctx.a.appPackage != null) {
            sampling();
        }
    }

    // ---- sampling -------------------------------------------------------------------------------

    private static final String SAMPLING = "management.tracing.sampling.probability";

    /**
     * Spring Boot keeps 10% of traces by default. Every log line still carries a trace id, so 9 in 10 of them
     * point at a trace that was never exported.
     */
    private void sampling() {
        String value = ctx.a.springProperties.get(SAMPLING);
        if (value != null) {
            double p;
            try {
                p = Double.parseDouble(value.strip());
            } catch (NumberFormatException e) {
                return; // a placeholder like ${TRACING_SAMPLE_RATE}: decided at deploy time
            }
            if (p < 1.0) {
                ctx.order(Rules.TRACING_SAMPLING, "sampling|" + value).at(applicationYml()[0], 1)
                        .title(SAMPLING + "=" + value + ": only " + Math.round(p * 100) + "% of traces are exported, "
                                + "the trace ids in the other log lines lead nowhere")
                        .suggestion("Use 1.0 and let the collector sample (tail sampling keeps errors and slow requests), "
                                + "or accept that most trace ids in logs cannot be opened.");
            }
            return;
        }
        // set per profile (application-prod.yml) or by environment: not ours to judge
        for (Path dir : SpringProperties.resourceDirs(ctx.a.project.root())) {
            try (var files = Files.list(dir)) {
                if (files.filter(p -> p.getFileName().toString().startsWith("application"))
                        .anyMatch(p -> SetupRules.read(p).contains("probability"))) {
                    return;
                }
            } catch (java.io.IOException e) {
                return;
            }
        }
        String[] yml = applicationYml();
        OrderBuilder b = ctx.order(Rules.TRACING_SAMPLING, "sampling|unset").at(yml[0], 1)
                .title("No tracing sampling set: Spring Boot exports 10% of traces, so 9 in 10 trace ids in the logs lead nowhere")
                .suggestion("Sets 1.0 (every trace exported). With high traffic, sample in the collector instead (tail sampling).");
        String snippet = "management:\n  tracing:\n    sampling:\n      probability: 1.0\n";
        Path file = ctx.a.project.root().resolve(yml[0]);
        if (Files.exists(file)) {
            String text = SetupRules.read(file);
            b.edit(Edit.replace(yml[0], 0, text.length(), SetupRules.mergeYaml(text, snippet)));
        } else {
            b.edit(Edit.create(yml[0], snippet));
        }
    }

    /** {relative path} of the service's base application.yaml/.yml (existing or to create). */
    private String[] applicationYml() {
        List<Path> resources = SpringProperties.resourceDirs(ctx.a.project.root());
        Path dir = resources.isEmpty() ? ctx.a.project.root().resolve("src/main/resources") : resources.getFirst();
        Path yml = Files.exists(dir.resolve("application.yaml")) ? dir.resolve("application.yaml") : dir.resolve("application.yml");
        return new String[]{ctx.a.project.root().relativize(yml).toString().replace('\\', '/')};
    }

    // ---- OpenFeign ------------------------------------------------------------------------------

    /** @FeignClient interfaces propagate the trace only with feign-micrometer on the classpath. */
    private void feign() {
        if (ctx.a.project.probe().hasClass("feign.micrometer.MicrometerObservationCapability")) {
            return;
        }
        OrderBuilder b = null;
        for (ClassInfo c : ctx.methodsOwners()) {
            if (c.decl().getLeadingAnnotations().stream().noneMatch(a -> Annotations.is(a, "org.springframework.cloud.openfeign.FeignClient"))) {
                continue;
            }
            int line = Pos.line(c.decl());
            if (ctx.ignored(c.file(), line, Rules.HTTP_CLIENT_UNTRACED)) {
                continue;
            }
            if (b == null) {
                b = ctx.order(Rules.HTTP_CLIENT_UNTRACED, "feign").at(c.file().relPath(), line)
                        .title("OpenFeign clients without feign-micrometer: no traceparent header, the called service starts a new trace")
                        .suggestion("Add implementation(\"io.github.openfeign:feign-micrometer\") (version from the Spring Cloud BOM); "
                                + "Spring Cloud OpenFeign then instruments every client.");
            } else {
                b.location(c.file().relPath(), line, Rules.HTTP_CLIENT_UNTRACED);
            }
        }
    }

    // ---- HTTP clients ---------------------------------------------------------------------------

    private record Client(String kind, String builderType, String builderImport, String param) {
    }

    private static final Client REST_CLIENT = new Client("RestClient", "RestClient.Builder", null, "restClientBuilder");
    private static final Client WEB_CLIENT = new Client("WebClient", "WebClient.Builder", null, "webClientBuilder");
    private static final Client REST_TEMPLATE = new Client("RestTemplate", "RestTemplateBuilder", REST_TEMPLATE_BUILDER, "restTemplateBuilder");

    /** Which client a construct creates, and the replacement expression using the injected builder; null if none. */
    private static String[] untracedClient(J tree, SourceFileInfo f) {
        if (tree instanceof J.MethodInvocation mi && mi.getSelect() instanceof J.Identifier sel
                && (sel.getSimpleName().equals("RestClient") || sel.getSimpleName().equals("WebClient"))
                && mi.getMethodType() != null && mi.getMethodType().hasFlags(Flag.Static)) {
            String kind = sel.getSimpleName();
            String param = kind.equals("RestClient") ? REST_CLIENT.param() : WEB_CLIENT.param();
            List<Expression> args = mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
            if (mi.getSimpleName().equals("builder") && args.isEmpty()) {
                return new String[]{kind, param};
            }
            if (mi.getSimpleName().equals("create") && args.isEmpty()) {
                return new String[]{kind, param + ".build()"};
            }
            if (mi.getSimpleName().equals("create") && args.size() == 1 && TypeUtils.isString(args.getFirst().getType())) {
                return new String[]{kind, param + ".baseUrl(" + f.slice(Pos.start(args.getFirst()), Pos.end(args.getFirst())) + ").build()"};
            }
            return new String[]{kind, null};
        }
        if (tree instanceof J.MethodInvocation mi && mi.getMethodType() != null && mi.getMethodType().hasFlags(Flag.Static)) {
            String owner = mi.getMethodType().getDeclaringType().getFullyQualifiedName();
            if (owner.equals("java.net.http.HttpClient") && Set.of("newHttpClient", "newBuilder").contains(mi.getSimpleName())) {
                return new String[]{"java.net.http.HttpClient", null};
            }
            if ((owner.equals("org.apache.hc.client5.http.impl.classic.HttpClients") || owner.equals("org.apache.http.impl.client.HttpClients"))
                    && Set.of("createDefault", "custom", "createSystem", "createMinimal").contains(mi.getSimpleName())) {
                return new String[]{"Apache HttpClient", null};
            }
        }
        if (tree instanceof J.NewClass nc && (TypeUtils.isOfClassType(nc.getType(), "okhttp3.OkHttpClient")
                || TypeUtils.isOfClassType(nc.getType(), "okhttp3.OkHttpClient$Builder"))) {
            return new String[]{"OkHttpClient", null};
        }
        if (tree instanceof J.NewClass nc && TypeUtils.isOfClassType(nc.getType(), "org.springframework.web.client.RestTemplate")) {
            boolean noArgs = nc.getArguments() == null || nc.getArguments().stream().allMatch(a -> a instanceof J.Empty);
            return new String[]{"RestTemplate", noArgs ? REST_TEMPLATE.param() + ".build()" : null};
        }
        return null;
    }

    private void httpClient(MethodInfo m, J tree, String[] hit, int ordinal) {
        SourceFileInfo f = m.file();
        int line = Pos.line(tree);
        if (ctx.ignored(f, line, Rules.HTTP_CLIENT_UNTRACED)) {
            return;
        }
        String other = switch (hit[0]) {
            case "java.net.http.HttpClient" -> "Build a RestClient from the injected RestClient.Builder (instrumented; "
                    + "requestFactory(new JdkClientHttpRequestFactory(httpClient)) keeps this client), or instrument it: "
                    + "JavaHttpClientTelemetry.create(openTelemetry).newHttpClient(httpClient) (opentelemetry-java-http-client).";
            case "Apache HttpClient" -> "Build a RestClient from the injected RestClient.Builder with "
                    + "requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient)): the RestClient observation adds the trace.";
            case "OkHttpClient" -> "Instrument it: OkHttpTelemetry.create(openTelemetry).newCallFactory(client) "
                    + "(opentelemetry-okhttp-3.0), or move the calls to an injected RestClient.Builder.";
            default -> null;
        };
        if (other != null) {
            ctx.order(Rules.HTTP_CLIENT_UNTRACED, m, line, "client" + ordinal)
                    .title(hit[0] + " sends no traceparent header: the called service starts a new trace")
                    .suggestion(other)
                    .context(ctx.context(m, Pos.start(tree), Text.oneLine(f.slice(Pos.start(tree), Pos.end(tree)), 160), null));
            return;
        }
        Client client = switch (hit[0]) {
            case "RestClient" -> REST_CLIENT;
            case "WebClient" -> WEB_CLIENT;
            default -> REST_TEMPLATE;
        };
        OrderBuilder b = ctx.order(Rules.HTTP_CLIENT_UNTRACED, m, line, "client" + ordinal)
                .title(hit[0] + " built by hand: no traceparent header, the called service starts a new trace")
                .context(ctx.context(m, Pos.start(tree), Text.oneLine(f.slice(Pos.start(tree), Pos.end(tree)), 160), null));
        boolean constructorOfBean = m.decl().isConstructor() && isBean(m.owner());
        boolean beanMethod = m.decl().getLeadingAnnotations().stream()
                .anyMatch(a -> Annotations.is(a, "org.springframework.context.annotation.Bean"));
        boolean builderAvailable = client.builderImport() == null || ctx.a.project.probe().hasClass(client.builderImport());
        if (hit[1] == null || !(constructorOfBean || beanMethod) || !builderAvailable) {
            b.suggestion("Inject Spring's " + client.builderType() + " (instrumented with tracing) and build the client from it"
                    + (builderAvailable ? "" : "; add spring-boot-starter-restclient") + ".");
            return;
        }
        b.edit(Edit.replace(f.relPath(), Pos.start(tree), Pos.end(tree), hit[1]));
        Edit param = addParameter(m, client.builderType(), client.param());
        if (param != null) {
            b.edit(param);
        }
        if (client.builderImport() != null) {
            b.edits(JavaEdits.imports(f, client.builderImport()));
        }
    }

    /** Inserts {@code Type name} as the last parameter (unless a parameter with that name exists). */
    private static Edit addParameter(MethodInfo m, String type, String name) {
        SourceFileInfo f = m.file();
        List<Statement> params = m.decl().getParameters();
        for (Statement p : params) {
            if (p instanceof J.VariableDeclarations vd && vd.getVariables().getFirst().getSimpleName().equals(name)) {
                return null;
            }
        }
        if (params.isEmpty() || params.getFirst() instanceof J.Empty) {
            int open = f.text().indexOf('(', Pos.end(m.decl().getName()));
            return Edit.insert(f.relPath(), open + 1, type + " " + name);
        }
        return Edit.insert(f.relPath(), Pos.end(params.getLast()), ", " + type + " " + name);
    }

    private static boolean isBean(ClassInfo c) {
        return c.decl().getLeadingAnnotations().stream().anyMatch(a -> BEAN_ANNOTATIONS.contains(a.getSimpleName()));
    }

    private void fieldInitializers() {
        for (ClassInfo c : ctx.a.index.classes()) {
            if (c.fqn().startsWith(ctx.a.supportPackage + ".")) {
                continue;
            }
            for (Statement s : c.decl().getBody().getStatements()) {
                if (!(s instanceof J.VariableDeclarations vd)) {
                    continue;
                }
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    Expression init = v.getInitializer();
                    String[] hit = init == null ? null : untracedClient(init, c.file());
                    if (hit == null) {
                        continue;
                    }
                    int line = Pos.line(init);
                    if (!ctx.ignored(c.file(), line, Rules.HTTP_CLIENT_UNTRACED)) {
                        ctx.order(Rules.HTTP_CLIENT_UNTRACED, c.fqn() + "|field|" + v.getSimpleName())
                                .at(c.file().relPath(), line)
                                .title(hit[0] + " in a field initializer: no traceparent header on outgoing calls")
                                .suggestion("Build it in the constructor from an injected, instrumented builder ("
                                        + (hit[0].equals("RestTemplate") ? "RestTemplateBuilder" : hit[0] + ".Builder") + ").");
                    }
                }
            }
        }
    }

    // ---- per-method scan: HTTP clients + job launches -------------------------------------------

    private void scan(MethodInfo m) {
        SourceFileInfo f = m.file();
        String body = f.slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
        boolean propagates = body.contains("TRACEPARENT") || body.contains("TraceContextEnv");
        int[] ordinal = {0};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                if (ctx.enabled(Rules.HTTP_CLIENT_UNTRACED)) {
                    String[] hit = untracedClient(mi, f);
                    if (hit != null) {
                        httpClient(m, mi, hit, ordinal[0]++);
                        return mi; // the builder chain below is replaced as a whole
                    }
                }
                if (ctx.enabled(Rules.JOB_LAUNCH_UNTRACED) && !propagates && mi.getMethodType() != null) {
                    String owner = mi.getMethodType().getDeclaringType().getFullyQualifiedName();
                    if (mi.getSimpleName().equals("withImage") && owner.startsWith("io.fabric8.kubernetes.api.model.")) {
                        fabric8(m, mi, ordinal[0]++);
                    } else if (mi.getSimpleName().equals("image") && owner.startsWith("io.kubernetes.client.openapi.models.V1Container")) {
                        jobOrder(m, mi, ordinal[0]++, "Kubernetes container (official client)")
                                .suggestion("Add the trace to the container env: TraceContextEnv.current().forEach((k, v) -> "
                                        + "container.addEnvItem(new V1EnvVar().name(k).value(v)));");
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass nc, Integer p) {
                if (ctx.enabled(Rules.HTTP_CLIENT_UNTRACED)) {
                    String[] hit = untracedClient(nc, f);
                    if (hit != null) {
                        httpClient(m, nc, hit, ordinal[0]++);
                    }
                }
                if (ctx.enabled(Rules.JOB_LAUNCH_UNTRACED) && !propagates
                        && TypeUtils.isOfClassType(nc.getType(), "java.lang.ProcessBuilder")) {
                    processBuilder(m, nc, getCursor(), ordinal[0]++);
                }
                return super.visitNewClass(nc, p);
            }
        }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
    }

    private OrderBuilder jobOrder(MethodInfo m, J tree, int ordinal, String what) {
        // in a builder chain the invocation starts at the chain's first line: anchor on the method name instead
        int line = tree instanceof J.MethodInvocation mi ? Pos.line(mi.getName()) : Pos.line(tree);
        OrderBuilder b = ctx.order(Rules.JOB_LAUNCH_UNTRACED, m, line, "job" + ordinal)
                .title(what + " started without TRACEPARENT: the job runs as a new, unrelated trace")
                .context(ctx.context(m, Pos.start(tree), Text.oneLine(tree.printTrimmed(), 160), null));
        ctx.dependOnStarter(b);
        return b;
    }

    /** {@code .withImage(x)} → {@code .withImage(x).addAllToEnv(<trace env vars>)}. */
    private void fabric8(MethodInfo m, J.MethodInvocation withImage, int ordinal) {
        if (ctx.ignored(m.file(), Pos.line(withImage.getName()), Rules.JOB_LAUNCH_UNTRACED)) {
            return;
        }
        SourceFileInfo f = m.file();
        String v = EntrypointRules.unique("env", ctx.vars(m, Pos.start(withImage)).keySet());
        OrderBuilder b = jobOrder(m, withImage, ordinal, "Kubernetes container (fabric8)");
        b.edit(Edit.insert(f.relPath(), Pos.end(withImage), "\n" + Text.indentAt(f.text(), Pos.start(withImage))
                + Text.indentUnit(f.text()) + Text.indentUnit(f.text()) + ".addAllToEnv(TraceContextEnv.current().entrySet().stream()"
                + ".map(" + v + " -> new EnvVar(" + v + ".getKey(), " + v + ".getValue(), null)).toList())"));
        b.edits(JavaEdits.imports(f, ctx.support("TraceContextEnv"), "io.fabric8.kubernetes.api.model.EnvVar"));
    }

    /** {@code ProcessBuilder pb = new ProcessBuilder(..);} → next line {@code pb.environment().putAll(TraceContextEnv.current());}. */
    private void processBuilder(MethodInfo m, J.NewClass nc, Cursor cursor, int ordinal) {
        SourceFileInfo f = m.file();
        if (ctx.ignored(f, Pos.line(nc), Rules.JOB_LAUNCH_UNTRACED)) {
            return;
        }
        OrderBuilder b = jobOrder(m, nc, ordinal, "Process");
        Cursor parent = cursor.getParentTreeCursor();
        if (parent.getValue() instanceof J.VariableDeclarations.NamedVariable var
                && parent.getParentTreeCursor().getValue() instanceof J.VariableDeclarations decl
                && parent.getParentTreeCursor().getParentTreeCursor().getValue() instanceof J.Block) {
            int end = JavaEdits.statementEnd(f.text(), decl);
            b.edit(Edit.insert(f.relPath(), Text.nextLine(f.text(), end - 1), Text.indentAt(f.text(), Pos.start(decl))
                    + var.getSimpleName() + ".environment().putAll(TraceContextEnv.current());\n"));
            b.edits(JavaEdits.imports(f, ctx.support("TraceContextEnv")));
        } else {
            b.suggestion("Assign the ProcessBuilder to a variable and call pb.environment().putAll(TraceContextEnv.current()).");
        }
    }

    // ---- Kafka ----------------------------------------------------------------------------------

    private void kafka() {
        List<String[]> templateUses = new ArrayList<>();
        for (ClassInfo c : ctx.methodsOwners()) {
            for (Statement s : c.decl().getBody().getStatements()) {
                if (s instanceof J.VariableDeclarations vd && TypeUtils.isAssignableTo("org.springframework.kafka.core.KafkaOperations", vd.getType())) {
                    templateUses.add(new String[]{c.file().relPath(), String.valueOf(Pos.line(vd))});
                }
            }
        }
        List<Entrypoint> listeners = ctx.a.entrypoints.stream().filter(ep -> ep.kind() == Entrypoint.Kind.KAFKA).toList();
        Map<String, String> props = ctx.a.springProperties;
        Map<String, String> missing = new LinkedHashMap<>();
        if (!templateUses.isEmpty() && !"true".equals(props.get("spring.kafka.template.observation-enabled"))) {
            missing.put("template", "observation-enabled");
        }
        if (!listeners.isEmpty() && !"true".equals(props.get("spring.kafka.listener.observation-enabled"))) {
            missing.put("listener", "observation-enabled");
        }
        if (missing.isEmpty()) {
            return;
        }
        String rel = applicationYml()[0];
        Path yml = ctx.a.project.root().resolve(rel);
        OrderBuilder b = ctx.order(Rules.KAFKA_OBSERVATION_DISABLED, "kafka|" + String.join(",", missing.keySet()))
                .at(rel, 1)
                .title("Kafka " + String.join(" and ", missing.keySet()) + " without observation: the trace stops at the topic");
        StringBuilder snippet = new StringBuilder("spring:\n  kafka:\n");
        missing.keySet().forEach(k -> snippet.append("    ").append(k).append(":\n      observation-enabled: true\n"));
        if (Files.exists(yml)) {
            String text = SetupRules.read(yml);
            b.edit(Edit.replace(rel, 0, text.length(), SetupRules.mergeYaml(text, snippet.toString())));
        } else {
            b.edit(Edit.create(rel, snippet.toString()));
        }
        templateUses.forEach(t -> b.location(t[0], Integer.parseInt(t[1]), Rules.KAFKA_OBSERVATION_DISABLED));
        listeners.forEach(ep -> b.location(ep.method().file().relPath(), ep.method().line(), Rules.KAFKA_OBSERVATION_DISABLED));
        if (missing.containsKey("listener")) {
            ctx.kafkaListenerObservationPlanned = true;
        }
    }
}
