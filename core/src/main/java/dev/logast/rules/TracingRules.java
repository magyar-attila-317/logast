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
    /** Boot 4's RestTemplateBuilder; Boot 3 has it in org.springframework.boot.web.client (#98). */
    private static final String REST_TEMPLATE_BUILDER = "org.springframework.boot.restclient.RestTemplateBuilder";
    private static final String BOOT3_REST_TEMPLATE_BUILDER = "org.springframework.boot.web.client.RestTemplateBuilder";

    private final RuleContext ctx;

    TracingRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (ctx.enabled(Rules.KAFKA_OBSERVATION_DISABLED)) {
            kafka();
        }
        if (ctx.enabled(Rules.RABBIT_OBSERVATION_DISABLED)) {
            rabbit();
        }
        observedAnnotations();   // always: whether @Observed / @NewSpan count as observing (EntrypointRules)
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            scan(m);
            if (ctx.enabled(Rules.SPAN_NOT_CLOSED) && !m.owner().fqn().startsWith(ctx.a.supportPackage + ".")) {
                spans(m);
            }
            if (ctx.enabled(Rules.SPAN_NAME_DYNAMIC) && !m.owner().fqn().startsWith(ctx.a.supportPackage + ".")) {
                spanNames(m);
            }
        }
        if (ctx.enabled(Rules.TRACING_EXPORT_UNCONFIGURED) && ctx.a.appPackage != null) {
            export();
        }
        // with the logging pack, SETUP_LOGBACK_XML replaces the file with Spring Boot's structured logging (MDC included)
        if (ctx.enabled(Rules.TRACE_ID_NOT_LOGGED) && !ctx.enabled(Rules.SETUP_LOGBACK_XML) && ctx.a.appPackage != null) {
            logPatterns();
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
        for (Path dir : java.util.Optional.ofNullable(SpringProperties.appResources(ctx.a.project.root())).stream().toList()) {
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
        {
            ServiceYaml.merge(ctx, b, snippet);
        }
    }

    // ---- @Observed / @NewSpan ---------------------------------------------------------------------------------------

    static final String OBSERVED = "io.micrometer.observation.annotation.Observed";
    static final String NEW_SPAN = "io.micrometer.tracing.annotation.NewSpan";
    private static final String ANNOTATIONS_ENABLED = "management.observations.annotations.enabled";

    /**
     * {@code @Observed} or {@code @NewSpan} on the method, or {@code @Observed} on its class (which the aspect applies to
     * the methods a proxy can intercept: not constructors, private or static ones).
     */
    static boolean observedAnnotation(MethodInfo m) {
        return observedAnnotation(m, true);
    }

    /**
     * {@code @Observed} on the method or its class (as {@link #observedAnnotation(MethodInfo)}), and with
     * {@code newSpan} also {@code @NewSpan}, which yields a span but no timer.
     */
    static boolean observedAnnotation(MethodInfo m, boolean newSpan) {
        if (m.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, OBSERVED) || newSpan && Annotations.is(a, NEW_SPAN))) {
            return true;
        }
        return !m.decl().isConstructor() && !m.decl().hasModifier(J.Modifier.Type.Private) && !m.decl().hasModifier(J.Modifier.Type.Static)
                && m.owner().decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, OBSERVED));
    }

    static final String TIMED = "io.micrometer.core.annotation.Timed";
    static final String COUNTED = "io.micrometer.core.annotation.Counted";

    /** {@code @Timed} or {@code @Counted} on the method: Micrometer's TimedAspect / CountedAspect meter it. */
    static boolean meterAnnotation(MethodInfo m) {
        return m.hasAnnotation(TIMED) || m.hasAnnotation(COUNTED);
    }

    /**
     * OBSERVED_ANNOTATION_INACTIVE: Spring Boot processes {@code @Observed}, {@code @NewSpan}, {@code @Timed} and
     * {@code @Counted} only with {@code management.observations.annotations.enabled: true} and aspectjweaver on the
     * classpath (Boot reference, Observability, "Micrometer Observation Annotations support": "To enable scanning of
     * observability annotations like @Observed, @Timed, @Counted, @MeterTag and @NewSpan, set the
     * management.observations.annotations.enabled property to true. A dependency on org.aspectj:aspectjweaver [...] is
     * also required"; ObservationAutoConfiguration.ObservedAspectConfiguration, MicrometerTracingAutoConfiguration
     * .SpanAspectConfiguration and MetricsAspectsAutoConfiguration are {@code @ConditionalOnClass(Advice.class)} and
     * {@code @ConditionalOnBooleanProperty} on it in Boot 3.5.7 and 4.1.2). Without them the annotated method runs
     * untraced and unmeasured. One switch for all four, so one rule in two packs: the tracing pack looks at
     * {@code @Observed} / {@code @NewSpan}, the metrics pack at {@code @Observed} (its timer), {@code @Timed} and
     * {@code @Counted}; with both selected it is one order. Sets the property and adds the AspectJ starter of the Boot
     * generation on the classpath; until both are there (or planned), no such annotation counts as observing or
     * measuring its method.
     */
    private void observedAnnotations() {
        List<MethodInfo> annotated = ctx.methods().stream()
                .filter(m -> !m.owner().fqn().startsWith(ctx.a.supportPackage + ".") && (observedAnnotation(m) || meterAnnotation(m)))
                .toList();
        if (annotated.isEmpty()) {
            return;
        }
        var probe = ctx.a.project.probe();
        boolean property = "true".equals(ctx.a.springProperties.get(ANNOTATIONS_ENABLED));
        Path build = buildFile(annotated.getFirst().file());
        for (ClassInfo c : ctx.a.index.classes()) {
            if (c.decl().getLeadingAnnotations().stream()
                    .anyMatch(a -> Annotations.is(a, "org.springframework.boot.autoconfigure.SpringBootApplication"))) {
                build = buildFile(c.file());
                break;
            }
        }
        BuildScripts scripts = build == null ? null : BuildScripts.of(ctx.a.project.root(), build);
        boolean weaver = probe.hasClass("org.aspectj.weaver.Advice") || scripts != null
                && (scripts.text().contains("aspectjweaver") || scripts.text().contains("spring-boot-starter-aspectj")
                || scripts.text().contains("spring-boot-starter-aop"));
        if (property && weaver) {
            return;
        }
        ctx.observedAnnotationsActive = false;
        if (!ctx.enabled(Rules.OBSERVED_ANNOTATION_INACTIVE)) {
            return;
        }
        // each pack switches on the annotations of its own concern: a run without the metrics pack leaves @Timed alone
        boolean tracing = ctx.policy.packSelected("tracing");
        boolean metrics = ctx.policy.packSelected("metrics");
        List<MethodInfo> concerned = annotated.stream().filter(m -> tracing && observedAnnotation(m)
                || metrics && (observedAnnotation(m, false) || meterAnnotation(m))).toList();
        if (concerned.isEmpty()) {
            return;
        }
        boolean observations = concerned.stream().anyMatch(TracingRules::observedAnnotation);
        boolean meters = concerned.stream().anyMatch(TracingRules::meterAnnotation);
        String rel = applicationYml()[0];
        List<String> missing = new ArrayList<>();
        if (!property) {
            missing.add(ANNOTATIONS_ENABLED + ": true");
        }
        if (!weaver) {
            missing.add("aspectjweaver");
        }
        OrderBuilder b = ctx.order(Rules.OBSERVED_ANNOTATION_INACTIVE, "observed-annotations").at(rel, 1)
                .title((observations ? "@Observed / @NewSpan" : "") + (observations && meters ? " / " : "")
                        + (meters ? "@Timed / @Counted" : "") + " do nothing without " + String.join(" and ", missing)
                        + ": the annotated methods run without " + (observations && meters ? "an observation, span, timer or counter"
                        : observations ? "an observation or span" : "a timer or counter"))
                .doneWhen(ANNOTATIONS_ENABLED + " is true in the service's configuration and aspectjweaver is on the classpath "
                        + "or a dependency (spring-boot-starter-aspectj, Boot 3: spring-boot-starter-aop)");
        for (MethodInfo m : concerned) {
            if (!ctx.ignored(m.file(), m.line(), Rules.OBSERVED_ANNOTATION_INACTIVE)) {
                b.location(m.file().relPath(), m.line(), Rules.OBSERVED_ANNOTATION_INACTIVE);
            }
        }
        // the Boot generation on the classpath names the starter that brings aspectjweaver (renamed in Boot 4)
        String starter = probe.hasClass("org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
                || probe.hasClass("org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAspectsAutoConfiguration")
                ? "org.springframework.boot:spring-boot-starter-aspectj"
                : probe.hasClass("org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration")
                || probe.hasClass("org.springframework.boot.actuate.autoconfigure.metrics.MetricsAspectsAutoConfiguration")
                ? "org.springframework.boot:spring-boot-starter-aop" : null;
        if (!weaver) {
            BuildScripts.Insert insert = starter == null || scripts == null ? null : scripts.insert(starter);
            if (insert == null) {
                String why = starter == null
                        ? " Spring Boot's observation auto-configuration is not on the classpath (Boot 4: spring-boot-starter-opentelemetry "
                        + "or the actuator; Boot 3: spring-boot-starter-actuator)."
                        : " No top-level dependencies {} block found: add " + starter + " manually."
                        + (scripts == null ? "" : scripts.unresolvedNote());
                b.suggestion("Set " + ANNOTATIONS_ENABLED + ": true and add aspectjweaver ("
                                + (starter == null ? "spring-boot-starter-aspectj in Boot 4, spring-boot-starter-aop in Boot 3" : starter)
                                + ")." + why);
                return;
            }
            b.edit(insert.edit());
        }
        if (!property) {
            ServiceYaml.merge(ctx, b, "management:\n  observations:\n    annotations:\n      enabled: true\n");
        }
        b.suggestion((property ? "Adds " : "Sets " + ANNOTATIONS_ENABLED + ": true" + (weaver ? "" : " and adds "))
                + (weaver ? "" : starter + " (aspectjweaver)")
                + (meters ? "" : ". This also switches on @Timed / @Counted where they are used")
                + (observations ? "" : ". This also switches on @Observed / @NewSpan where they are used")
                + ". A method Spring observes already (controller, @Scheduled) gets a second "
                + (observations ? "observation" : "timer") + ": remove the annotation there."
                + (weaver ? "" : " Run `logast classpath .` afterwards."));
        ctx.observedAnnotationsActive = true;   // planned: the annotated methods count as observed
    }

    // ---- manual spans and observations ----------------------------------------------------------------------------

    /** What ends, fails and scopes one kind of manually started span. */
    private record SpanKind(String type, String label, Set<String> starts, String end, List<String> error, List<String> scope,
                            boolean scoped, String template) {
    }

    private static final List<SpanKind> SPAN_KINDS = List.of(
            new SpanKind("io.micrometer.observation.Observation", "Observation", Set.of("start"), "stop", List.of("error"),
                    List.of("openScope"), false,
                    "Use %1$s.observe(() -> ...) (it opens the scope, records the error and stops it), or: try (Observation.Scope "
                            + "scope = %1$s.openScope()) { ... } catch (RuntimeException e) { %1$s.error(e); throw e; } finally { %1$s.stop(); }"),
            new SpanKind("io.micrometer.tracing.Span", "Span", Set.of("start"), "end", List.of("error"), List.of(), false,
                    "try (Tracer.SpanInScope ws = tracer.withSpan(%1$s)) { ... } catch (RuntimeException e) { %1$s.error(e); throw e; } "
                            + "finally { %1$s.end(); }"),
            new SpanKind("io.micrometer.tracing.ScopedSpan", "ScopedSpan", Set.of("startScopedSpan"), "end", List.of("error"), List.of(),
                    true, "try { ... } catch (RuntimeException e) { %1$s.error(e); throw e; } finally { %1$s.end(); }"),
            new SpanKind("io.opentelemetry.api.trace.Span", "Span", Set.of("startSpan"), "end", List.of("recordException", "setStatus"),
                    List.of("makeCurrent"), false,
                    "try (Scope scope = %1$s.makeCurrent()) { ... } catch (RuntimeException e) { %1$s.recordException(e); "
                            + "%1$s.setStatus(StatusCode.ERROR); throw e; } finally { %1$s.end(); }"));

    private static SpanKind spanKind(Expression init) {
        if (init instanceof J.MethodInvocation mi) {
            for (SpanKind k : SPAN_KINDS) {
                if (k.starts().contains(mi.getSimpleName()) && TypeUtils.isOfClassType(mi.getType(), k.type())) {
                    return k;
                }
            }
        }
        return null;
    }

    /**
     * SPAN_NOT_CLOSED: a span or observation started by hand into a local variable that is not ended in a finally
     * block, records no failure, or is never put in scope. Micrometer's examples stop it in {@code finally}, call
     * {@code error(e)} and open a scope (Micrometer Observation reference, "Observation Handler"; Micrometer Tracing
     * reference, API: "Once done remember to end the span"). Not reported when the variable leaves the method
     * (returned, passed on, ended in a callback): its lifecycle continues elsewhere.
     */
    private void spans(MethodInfo m) {
        SourceFileInfo f = m.file();
        Map<String, SpanKind> started = new LinkedHashMap<>();
        Map<String, J.VariableDeclarations.NamedVariable> decls = new HashMap<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, Integer p) {
                SpanKind k = spanKind(v.getInitializer());
                if (k != null && !started.containsKey(v.getSimpleName())) {
                    started.put(v.getSimpleName(), k);
                    decls.put(v.getSimpleName(), v);
                }
                return super.visitVariable(v, p);
            }
        }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
        for (Map.Entry<String, SpanKind> e : started.entrySet()) {
            String v = e.getKey();
            SpanKind k = e.getValue();
            SpanUse use = spanUse(m, v, k);
            if (use.escapes) {
                continue;
            }
            List<String> problems = new ArrayList<>();
            if (!use.ended) {
                problems.add("never " + (k.end().equals("stop") ? "stopped" : "ended") + ", so it is never reported");
            } else if (!use.endedInFinally) {
                problems.add(k.end() + "() is not in a finally block, so an exception leaves it open");
            }
            if (!use.error) {
                problems.add("no failure is recorded on it (" + String.join(" / ", k.error()) + "), so a failed call looks successful");
            }
            if (!use.scope && !k.scoped()) {
                problems.add("it is never put in scope (" + (k.scope().isEmpty() ? "tracer.withSpan" : k.scope().getFirst())
                        + "), so log lines and spans inside do not belong to it");
            }
            if (problems.isEmpty()) {
                continue;
            }
            J.VariableDeclarations.NamedVariable decl = decls.get(v);
            int line = Pos.line(decl);
            if (ctx.ignored(f, line, Rules.SPAN_NOT_CLOSED)) {
                continue;
            }
            ctx.orderNth(Rules.SPAN_NOT_CLOSED, m, line, "span")
                    .title(k.label() + " " + v + " in " + m.label() + " is started by hand: " + String.join("; ", problems))
                    .suggestion(k.template().formatted(v))
                    .doneWhen(v + "." + k.end() + "() runs in a finally block, a catch records the failure ("
                            + String.join(" / ", k.error()) + ")" + (k.scoped() ? "" : " and it is put in scope")
                            + ", or the variable leaves the method");
        }
    }

    private static final class SpanUse {
        boolean ended;
        boolean endedInFinally;
        boolean error;
        boolean scope;
        boolean escapes;
    }

    private static SpanUse spanUse(MethodInfo m, String v, SpanKind k) {
        SpanUse use = new SpanUse();
        Set<Object> finallies = Collections.newSetFromMap(new IdentityHashMap<>());
        new JavaIsoVisitor<Integer>() {
            private boolean isVar(Expression x) {
                return x instanceof J.Identifier id && id.getSimpleName().equals(v);
            }

            @Override
            public J.Try visitTry(J.Try t, Integer p) {
                if (t.getFinally() != null) {
                    finallies.add(t.getFinally());
                }
                return super.visitTry(t, p);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                if (isVar(mi.getSelect())) {
                    if (mi.getSimpleName().equals(k.end())) {
                        use.ended = true;
                        use.endedInFinally |= getCursor().getPathAsStream().anyMatch(finallies::contains);
                        // ended in a callback: an asynchronous lifecycle
                        use.escapes |= getCursor().getPathAsStream().anyMatch(o -> o instanceof J.Lambda);
                    } else if (k.error().contains(mi.getSimpleName())) {
                        use.error = true;
                    } else if (k.scope().contains(mi.getSimpleName())) {
                        use.scope = true;
                    }
                }
                for (Expression arg : mi.getArguments()) {
                    if (isVar(arg)) {
                        if (mi.getSimpleName().equals("withSpan")) {
                            use.scope = true;
                        } else {
                            use.escapes = true;
                        }
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }

            @Override
            public J.Return visitReturn(J.Return r, Integer p) {
                use.escapes |= isVar(r.getExpression());
                return super.visitReturn(r, p);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment a, Integer p) {
                use.escapes |= isVar(a.getAssignment());
                return super.visitAssignment(a, p);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass nc, Integer p) {
                if (nc.getArguments() != null && nc.getArguments().stream().anyMatch(this::isVar)) {
                    use.escapes = true;
                }
                return super.visitNewClass(nc, p);
            }
        }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
        return use;
    }

    // ---- span and observation names ---------------------------------------------------------------------------

    private static final java.util.regex.Pattern ID_NAME = java.util.regex.Pattern.compile("(id|uuid)|[a-z][A-Za-z0-9]*(Id|ID|Uuid|UUID)");

    /** What a call names, or null: an observation's name or contextual name, a span's name. */
    private static String namedBy(J.MethodInvocation mi) {
        JavaType.Method mt = mi.getMethodType();
        if (mt == null || mi.getArguments().isEmpty() || !TypeUtils.isString(mi.getArguments().getFirst().getType())) {
            return null;
        }
        String decl = mt.getDeclaringType().getFullyQualifiedName().replace('$', '.');
        String n = mi.getSimpleName();
        return switch (decl) {
            case "io.micrometer.observation.Observation" -> n.equals("createNotStarted") || n.equals("start") ? "Observation name"
                    : n.equals("contextualName") ? "Observation contextual name" : null;
            case "io.micrometer.tracing.Span", "io.micrometer.tracing.Span.Builder" -> n.equals("name") ? "Span name" : null;
            case "io.micrometer.tracing.Tracer" -> n.equals("startScopedSpan") ? "Span name" : null;
            case "io.opentelemetry.api.trace.Tracer" -> n.equals("spanBuilder") ? "Span name" : null;
            case "io.opentelemetry.api.trace.Span" -> n.equals("updateName") ? "Span name" : null;
            default -> null;
        };
    }

    /** The values a string is built from: the operands of a concatenation, the arguments of String.format / formatted. */
    private static void parts(Expression e, List<Expression> out) {
        if (e instanceof J.Parentheses<?> p && p.getTree() instanceof Expression inner) {
            parts(inner, out);
        } else if (e instanceof J.Binary b && b.getOperator() == J.Binary.Type.Addition) {
            parts(b.getLeft(), out);
            parts(b.getRight(), out);
        } else if (e instanceof J.MethodInvocation mi && (mi.getSimpleName().equals("format") || mi.getSimpleName().equals("valueOf"))
                && mi.getMethodType() != null && TypeUtils.isOfClassType(mi.getMethodType().getDeclaringType(), "java.lang.String")) {
            mi.getArguments().stream().skip(mi.getSimpleName().equals("format") ? 1 : 0).forEach(a -> parts(a, out));
        } else if (e instanceof J.MethodInvocation mi && mi.getSimpleName().equals("formatted") && mi.getSelect() != null
                && TypeUtils.isString(mi.getSelect().getType())) {
            parts(mi.getSelect(), out);
            mi.getArguments().forEach(a -> parts(a, out));
        } else {
            out.add(e);
        }
    }

    /**
     * The name of an unbounded value (an id or personal data), or null: a vocabulary key with high cardinality (as
     * METRIC_HIGH_CARDINALITY_TAG decides, also through the key it is stored under), PII, or a value named like an id
     * ({@code id}, {@code orderId}, {@code uuid}).
     */
    private String unbounded(Expression value, String key) {
        if (DomainValues.isConstant(value)) {
            return null;
        }
        String name = DomainValues.derivedKey(value);
        Optional<String> valueKey = ctx.domain.keyOf(value);
        if (valueKey.map(ctx.policy::highCardinality).orElse(false)) {
            return name != null ? name : valueKey.get();
        }
        if (key != null && ctx.policy.highCardinality(ctx.policy.mdcAliases().getOrDefault(key, key))) {
            return name != null ? name : key;
        }
        if (ctx.domain.isPii(value) || name != null && ID_NAME.matcher(name).matches()) {
            return name;
        }
        return null;
    }

    /**
     * SPAN_NAME_DYNAMIC: an observation or span name built from an id, or an id among an observation's low-cardinality
     * key values. Micrometer names the observation's timer after it and tags it with every low-cardinality key value
     * (Micrometer Observation reference, "Observation Handler": "Only low cardinality key values are used as meter
     * tags"; high-cardinality values "can cause a cardinality explosion in the metrics backend"); a span name should be
     * "the most general string that identifies a (statistically) interesting class of Spans" (OpenTelemetry Tracing
     * API: get_account, not get_account/42). An id there makes one timer series and one span name per value. Only
     * names built from an id are reported: a name passed in by a caller, or built from a topic or type, is bounded.
     */
    private void spanNames(MethodInfo m) {
        SourceFileInfo f = m.file();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                String named = namedBy(mi);
                if (named != null) {
                    List<Expression> parts = new ArrayList<>();
                    parts(mi.getArguments().getFirst(), parts);
                    for (Expression part : parts) {
                        String id = unbounded(part, null);
                        if (id != null) {
                            report(mi, named + " built from " + id, "use a fixed name and put " + id
                                    + " into highCardinalityKeyValue(...) or a span tag");
                            break;
                        }
                    }
                } else if (mi.getSimpleName().equals("lowCardinalityKeyValue") && mi.getMethodType() != null
                        && TypeUtils.isAssignableTo("io.micrometer.observation.Observation", mi.getMethodType().getDeclaringType())) {
                    List<Expression> args = mi.getArguments();
                    if (args.size() == 1 && args.getFirst() instanceof J.MethodInvocation of && of.getSimpleName().equals("of")
                            && of.getArguments().size() == 2) {
                        args = of.getArguments();   // lowCardinalityKeyValue(KeyValue.of("order.id", orderId))
                    }
                    if (args.size() == 2) {
                        String key = args.getFirst() instanceof J.Literal l && l.getValue() instanceof String k ? k : null;
                        String id = unbounded(args.get(1), key);
                        if (id != null) {
                            report(mi, "Low-cardinality key value " + (key == null ? "" : "'" + key + "' ") + "is " + id,
                                    "use highCardinalityKeyValue(...) for it (on the span, not a meter tag)");
                        }
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }

            private void report(J.MethodInvocation mi, String what, String fix) {
                int line = Pos.line(mi.getName());   // in a chain: the line of this call, not of the chain's start
                if (ctx.ignored(f, line, Rules.SPAN_NAME_DYNAMIC)) {
                    return;
                }
                ctx.orderNth(Rules.SPAN_NAME_DYNAMIC, m, line, "name")
                        .title(what + " in " + m.label() + ": every value starts its own timer series and span name")
                        .suggestion("Keep names and low-cardinality key values bounded: " + fix + ".")
                        .doneWhen("the name is built from constants only and no id is a low-cardinality key value")
                        .context(ctx.context(m, Pos.start(mi), Text.oneLine(mi.printTrimmed(), 160), null));
            }
        }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
    }

    // ---- trace ids in a custom log pattern ---------------------------------------------------------------------

    /** What puts the trace id into a pattern: the MDC key, all of MDC, Spring Boot's correlation or default pattern. */
    private static final java.util.regex.Pattern CORRELATED = java.util.regex.Pattern.compile(
            "traceId|trace_id|%correlationId|LOG_CORRELATION_PATTERN|CONSOLE_LOG_PATTERN|FILE_LOG_PATTERN|%(X|mdc)(?!\\{)");
    private static final java.util.regex.Pattern PATTERN_ELEMENT = java.util.regex.Pattern.compile("<pattern>([\\s\\S]*?)</pattern>");
    private static final java.util.regex.Pattern XML_PROPERTY = java.util.regex.Pattern.compile(
            "<property\\s+name=\"([^\"]+)\"\\s+value=\"([^\"]*)\"");

    /**
     * TRACE_ID_NOT_LOGGED: Spring Boot puts the trace and span id into log lines through its own pattern
     * ({@code ${LOG_CORRELATION_PATTERN}}, from logging.pattern.correlation, inside defaults.xml's CONSOLE_LOG_PATTERN and
     * FILE_LOG_PATTERN, Boot 3.5 and 4.1; Boot reference, Tracing, "Logging Correlation IDs"). A logback(-spring).xml
     * with a text pattern of its own, or a logging.pattern.console / .file, without it drops them: the log lines cannot
     * be matched to a trace. Only without the logging pack, where SETUP_LOGBACK_XML replaces the file.
     */
    private void logPatterns() {
        Path root = ctx.a.project.root();
        Path resources = SpringProperties.appResources(root);
        if (resources == null || ctx.a.springProperties.get("logging.config") != null) {
            return;
        }
        boolean ownFile = false;
        for (String name : List.of("logback-spring.xml", "logback.xml")) {
            Path xml = resources.resolve(name);
            if (!Files.exists(xml)) {
                continue;
            }
            ownFile = true;
            String text = SetupRules.read(xml);
            Map<String, String> props = new HashMap<>();
            for (var pm = XML_PROPERTY.matcher(text); pm.find(); ) {
                props.put(pm.group(1), pm.group(2));
            }
            List<Integer> lines = new ArrayList<>();
            for (var pm = PATTERN_ELEMENT.matcher(text); pm.find(); ) {
                String pattern = pm.group(1);
                for (int i = 0; i < 3; i++) {   // ${NAME} of a <property> in the same file
                    for (var e : props.entrySet()) {
                        pattern = pattern.replace("${" + e.getKey() + "}", e.getValue());
                    }
                }
                // a JSON encoder's pattern provider ({"field": "%..."}) is not a text layout
                if (!pattern.strip().startsWith("{") && !CORRELATED.matcher(pattern).find()) {
                    lines.add(1 + (int) text.substring(0, pm.start()).chars().filter(c -> c == '\n').count());
                }
            }
            if (lines.isEmpty()) {
                continue;
            }
            String rel = root.relativize(xml).toString().replace('\\', '/');
            OrderBuilder b = ctx.order(Rules.TRACE_ID_NOT_LOGGED, "logback|" + rel).at(rel, lines.getFirst())
                    .title(name + " logs with a pattern of its own without the trace id: its log lines cannot be matched to a trace")
                    .suggestion("Add %X{traceId:-} %X{spanId:-} (or Spring Boot's ${LOG_CORRELATION_PATTERN:-}, which follows "
                            + "logging.pattern.correlation) to each <pattern>, or switch to structured logging "
                            + "(logging.structured.format.console), which writes every MDC value.")
                    .doneWhen("every text <pattern> of " + name + " contains traceId, %correlationId or ${LOG_CORRELATION_PATTERN}");
            lines.stream().skip(1).forEach(l -> b.location(rel, l, Rules.TRACE_ID_NOT_LOGGED));
        }
        // Spring Boot's logging.pattern.* only shape its own configuration, not a file of the service's
        for (String kind : ownFile ? List.<String>of() : List.of("console", "file")) {
            String pattern = ctx.a.springProperties.get("logging.pattern." + kind);
            if (pattern == null || ctx.a.springProperties.get("logging.structured.format." + kind) != null
                    || CORRELATED.matcher(pattern).find()) {
                continue;
            }
            ctx.order(Rules.TRACE_ID_NOT_LOGGED, "pattern|" + kind).at(applicationYml()[0], 1)
                    .title("logging.pattern." + kind + " replaces Spring Boot's pattern without the trace id: its log lines "
                            + "cannot be matched to a trace")
                    .suggestion("Add ${LOG_CORRELATION_PATTERN:-} (Spring Boot's correlation id, logging.pattern.correlation) or "
                            + "%X{traceId:-} %X{spanId:-} to it, or remove it.")
                    .doneWhen("logging.pattern." + kind + " contains traceId, %correlationId or ${LOG_CORRELATION_PATTERN}");
        }
    }

    // ---- trace export -------------------------------------------------------------------------------------------

    /**
     * TRACING_EXPORT_UNCONFIGURED: Micrometer Tracing with OpenTelemetry and Spring Boot's OTLP export on the
     * classpath, but no endpoint anywhere. Spring Boot creates the OTLP span exporter only when the endpoint property is
     * set (OtlpTracingConfigurations.ConnectionDetails is {@code @ConditionalOnProperty} on
     * management.opentelemetry.tracing.export.otlp.endpoint in Boot 4.0 and 4.1, management.otlp.tracing.endpoint in Boot
     * 3.5): spans are created and their ids logged, but sent nowhere. Also reports export switched off in the base
     * configuration. The endpoint differs per environment, so this only reports. The names follow the service's Boot
     * version (#97; application properties appendix, docs.spring.io/spring-boot/{3.5,4.0,4.1}/appendix/application-properties:
     * 3.5 has management.otlp.tracing.endpoint, management.tracing.enabled and management.otlp.tracing.export.enabled,
     * which 4.0 renames to management.opentelemetry.tracing.export.otlp.endpoint, management.tracing.export.enabled and
     * management.tracing.export.otlp.enabled); only an unknown version is read from the classpath.
     */
    private void export() {
        var probe = ctx.a.project.probe();
        // Boot 4: the module comes with spring-boot-starter-opentelemetry, which brings the bridge and the exporter at
        // runtime only (not on the compile classpath); Boot 3: the actuator always has the auto-configuration, the
        // bridge and the exporter are declared next to it
        boolean boot4 = probe.hasClass("org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration");
        boolean boot3 = probe.hasClass("org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration")
                && probe.hasClass("io.micrometer.tracing.otel.bridge.OtelTracer")
                && probe.hasClass("io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter");
        if (!boot4 && !boot3) {
            return;
        }
        int bootMajor = SetupRules.springBootVersion(ctx)[0];
        boolean boot4Names = bootMajor >= 4 || bootMajor == 0 && boot4;
        String endpoint = boot4Names ? "management.opentelemetry.tracing.export.otlp.endpoint" : "management.otlp.tracing.endpoint";
        List<String> switches = boot4Names ? List.of("management.tracing.export.enabled", "management.tracing.export.otlp.enabled")
                : List.of("management.tracing.enabled", "management.otlp.tracing.export.enabled");
        String rel = applicationYml()[0];
        for (String s : switches) {
            if ("false".equals(ctx.a.springProperties.get(s))) {
                ctx.order(Rules.TRACING_EXPORT_UNCONFIGURED, "export-off|" + s).at(rel, 1)
                        .title(s + ": false in the base configuration: no trace is exported, the trace ids in the logs lead nowhere")
                        .suggestion("Remove it, or switch it off only in the profiles of environments without a collector.")
                        .doneWhen(s + " is not false in the base configuration");
                return;
            }
        }
        String env = endpoint.toUpperCase(Locale.ROOT).replace('.', '_').replace("-", "");
        List<String> envNames = new ArrayList<>(List.of(env));
        // Boot 4 maps the OpenTelemetry SDK variables onto its properties (Boot reference, Observability, OpenTelemetry)
        if (boot4Names && probe.hasClass("org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetryEnvironmentVariableEnvironmentPostProcessor")) {
            envNames.addAll(List.of("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "OTEL_EXPORTER_OTLP_ENDPOINT"));
        }
        if (configuredAnywhere(endpoint) || mentionedInProject(envNames) || mentionedInProject(List.of("opentelemetry-javaagent"))
                || ctx.a.index.classes().stream().anyMatch(c -> !c.fqn().startsWith(ctx.a.supportPackage + ".")
                && c.file().text().contains("SpanExporter"))) {
            return;   // set per profile or by the deployment, exported by the agent, or an exporter bean of its own
        }
        ctx.order(Rules.TRACING_EXPORT_UNCONFIGURED, "export|" + endpoint).at(rel, 1)
                .title("Traces are exported nowhere: " + endpoint + " is not set, so Spring Boot creates no OTLP exporter and "
                        + "the trace ids in the logs lead nowhere")
                .suggestion("Set the collector's endpoint per environment: " + endpoint + " in application-<profile>.yml, or "
                        + String.join(" / ", envNames) + " in the deployment (e.g. http://otel-collector:4318/v1/traces).")
                .doneWhen(endpoint + " is set in an application*.yml/.properties, or " + String.join(" / ", envNames)
                        + " appears in the project's deployment files");
    }

    /** Some application*.yml/.yaml/.properties of the project (any module, any profile) sets {@code key}. */
    private boolean configuredAnywhere(String key) {
        for (Path dir : SpringProperties.resourceDirs(ctx.a.project.root())) {
            try (var files = Files.list(dir)) {
                if (files.filter(p -> p.getFileName().toString().startsWith("application"))
                        .anyMatch(p -> SpringProperties.allKeys(p).contains(key))) {
                    return true;
                }
            } catch (java.io.IOException e) {
                return false;
            }
        }
        return false;
    }

    private static final Set<String> SKIPPED_DIRS = Set.of(".git", ".gradle", ".idea", ".logast", "build", "target", "node_modules", "out");

    /** One of {@code names} appears in a text file of the project (deployment manifests, compose files, scripts, .env). */
    private boolean mentionedInProject(List<String> names) {
        Path root = ctx.a.project.root();
        try (var walk = Files.walk(root, 8)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        for (Path seg : root.relativize(p)) {
                            if (SKIPPED_DIRS.contains(seg.toString())) {
                                return false;
                            }
                        }
                        String n = p.getFileName().toString();
                        return !n.endsWith(".java") && !n.endsWith(".class") && !n.endsWith(".jar");
                    })
                    .filter(p -> {
                        try {
                            return Files.size(p) < 512 * 1024;
                        } catch (java.io.IOException e) {
                            return false;
                        }
                    })
                    .anyMatch(p -> {
                        String text = SetupRules.read(p);
                        return names.stream().anyMatch(text::contains);
                    });
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            return false;
        }
    }

    /** {relative path} of the service's config file where fixes go (the application module's, #47). */
    private String[] applicationYml() {
        return new String[]{ServiceYaml.path(ctx)};
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
        // Spring Boot provides the builder bean (traced, with codecs/customizers) only with its auto-configuration module
        String starter = client == WEB_CLIENT ? "org.springframework.boot:spring-boot-starter-webclient"
                : "org.springframework.boot:spring-boot-starter-restclient";
        boolean autoConfigured = autoConfigured(client);
        Edit addStarter = null;
        if (!autoConfigured) {
            Path build = buildFile(f);
            if (build != null) {
                BuildScripts scripts = BuildScripts.of(ctx.a.project.root(), build);   // with its apply from scripts (#80)
                BuildScripts.Insert insert = scripts.text().contains(starter.substring(starter.indexOf(':') + 1)) ? null
                        : scripts.insert(starter);
                addStarter = insert == null ? null : insert.edit();
            }
        }
        if (hit[1] == null || !(constructorOfBean || beanMethod) || !autoConfigured && addStarter == null) {
            b.suggestion("Inject Spring's " + client.builderType() + " (auto-configured by Spring Boot, already traced) and build "
                    + "the client from it" + (autoConfigured ? "" : "; that bean needs " + starter) + ".");
            return;
        }
        if (addStarter != null) {
            b.edit(addStarter);
            b.suggestion("Adds " + starter + ": Spring Boot then provides the " + client.builderType() + " bean (traced, with your "
                    + "codecs and customizers). Run `logast classpath .` afterwards.");
        }
        b.edit(Edit.replace(f.relPath(), Pos.start(tree), Pos.end(tree), hit[1]));
        Edit param = addParameter(m, client.builderType(), client.param());
        if (param != null) {
            b.edit(param);
        }
        if (client.builderImport() != null) {
            int bootMajor = SetupRules.springBootVersion(ctx)[0];
            b.edits(JavaEdits.imports(f, client == REST_TEMPLATE && bootMajor >= 1 && bootMajor <= 3
                    ? BOOT3_REST_TEMPLATE_BUILDER : client.builderImport()));
        }
    }

    /** Spring Boot's auto-configuration that provides the client's builder bean (Boot 4 module, or Boot 3 location). */
    private boolean autoConfigured(Client client) {
        var probe = ctx.a.project.probe();
        return client == WEB_CLIENT
                ? probe.hasClass("org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration")
                || probe.hasClass("org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration")
                : client == REST_CLIENT
                ? probe.hasClass("org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration")
                || probe.hasClass("org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration")
                : probe.hasClass("org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration")
                || probe.hasClass("org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration");
    }

    /** The Gradle build file of the module the source file belongs to. */
    private Path buildFile(SourceFileInfo f) {
        for (Path dir = f.absPath().getParent(); dir != null && dir.startsWith(ctx.a.project.root()); dir = dir.getParent()) {
            for (String n : List.of("build.gradle.kts", "build.gradle")) {
                if (Files.exists(dir.resolve(n))) {
                    return dir.resolve(n);
                }
            }
        }
        return null;
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
        {
            ServiceYaml.merge(ctx, b, snippet.toString());
        }
        templateUses.forEach(t -> b.location(t[0], Integer.parseInt(t[1]), Rules.KAFKA_OBSERVATION_DISABLED));
        listeners.forEach(ep -> b.location(ep.method().file().relPath(), ep.method().line(), Rules.KAFKA_OBSERVATION_DISABLED));
        if (missing.containsKey("listener")) {
            ctx.kafkaListenerObservationPlanned = true;
        }
    }

    /**
     * RABBIT_OBSERVATION_DISABLED: @RabbitListener without listener observation (off by default): each message runs
     * without the producer's trace. The listener container in use (simple by default, direct when
     * spring.rabbitmq.listener.type says so) gets observation-enabled. Batch listeners are not observed either way.
     */
    private void rabbit() {
        List<Entrypoint> listeners = ctx.a.entrypoints.stream().filter(ep -> ep.kind() == Entrypoint.Kind.RABBIT).toList();
        Map<String, String> props = ctx.a.springProperties;
        String type = "direct".equals(props.get("spring.rabbitmq.listener.type")) ? "direct" : "simple";
        if (listeners.isEmpty() || "true".equals(props.get("spring.rabbitmq.listener." + type + ".observation-enabled"))) {
            return;
        }
        String rel = applicationYml()[0];
        OrderBuilder b = ctx.order(Rules.RABBIT_OBSERVATION_DISABLED, "rabbit|" + type).at(rel, 1)
                .title("RabbitMQ listeners without observation: the trace stops at the queue");
        ServiceYaml.merge(ctx, b, "spring:\n  rabbitmq:\n    listener:\n      " + type + ":\n        observation-enabled: true\n");
        listeners.forEach(ep -> b.location(ep.method().file().relPath(), ep.method().line(), Rules.RABBIT_OBSERVATION_DISABLED));
        ctx.rabbitListenerObservationPlanned = true;
    }
}
