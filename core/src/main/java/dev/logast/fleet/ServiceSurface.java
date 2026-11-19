package dev.logast.fleet;

import dev.logast.Analysis;
import dev.logast.SpringProperties;
import dev.logast.graph.Constants;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.Pos;
import dev.logast.rules.MetricsMap;
import dev.logast.rules.Rules;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a service shows the other services (#113): its outgoing calls (HTTP clients, messages it sends), its
 * entrypoints (request mappings, listeners), whether each carries the trace on, and the outcomes it counts. Fleet
 * matches one service's calls against the others' entrypoints and compares the meters of the same outcome.
 *
 * @param calls   outgoing HTTP requests and messages with a known path, topic or queue
 * @param entries request mappings and listeners
 * @param counted outcomes the logs report that a meter counts
 * @param application its {@code spring.application.name}, a name other services' base URLs may use for it (#128); null
 *                    when not set (or in a snapshot written before it)
 */
public record ServiceSurface(List<Call> calls, List<Entry> entries, List<MetricsMap.Counted> counted, String application) {
    public ServiceSurface {
        calls = calls == null ? List.of() : List.copyOf(calls);
        entries = entries == null ? List.of() : List.copyOf(entries);
        counted = counted == null ? List.of() : List.copyOf(counted);
    }

    public ServiceSurface(List<Call> calls, List<Entry> entries, List<MetricsMap.Counted> counted) {
        this(calls, entries, counted, null);
    }

    /**
     * Why the trace does not go on at this side.
     *
     * @param rule  the rule whose fix closes it, or null when logast has none (a property to set by hand)
     * @param order the id of the order of this scan that fixes it, or null
     */
    public record Break(String what, String rule, String order, String fix) {
    }

    /**
     * An outgoing request or message.
     *
     * @param kind   http, kafka, rabbit or jms
     * @param verb   the HTTP method, null when unknown or not HTTP
     * @param target the path (with {@code {?}} for a value not known in the code), topic or queue
     * @param base   scheme and host when known: literally in the URI, from a property (application.yml, a profile file,
     *               the placeholder's default), the client's {@code baseUrl} / {@code rootUri}, OpenFeign's {@code url} or
     *               {@code name}; null when not (then the call is matched on its path only)
     * @param client RestClient, WebClient, RestTemplate, OpenFeign, HTTP interface, KafkaTemplate, RabbitTemplate, JmsTemplate
     * @param breaks null when the trace goes on
     */
    public record Call(String kind, String verb, String target, String base, String client, String method, String file,
                       int line, Break breaks) {
    }

    /**
     * An entrypoint another service can reach.
     *
     * @param kind   http, kafka, rabbit or jms
     * @param verb   the HTTP method; null for any (a {@code @RequestMapping} without one) and for messaging
     * @param target the path or topic / queue
     * @param breaks null when the entrypoint continues the caller's trace
     */
    public record Entry(String kind, String verb, String target, String label, String method, String file, int line,
                        Break breaks) {
    }

    private static final Map<String, String> REST_TEMPLATE = Map.ofEntries(
            Map.entry("getForObject", "GET"), Map.entry("getForEntity", "GET"), Map.entry("headForHeaders", "HEAD"),
            Map.entry("postForObject", "POST"), Map.entry("postForEntity", "POST"), Map.entry("postForLocation", "POST"),
            Map.entry("put", "PUT"), Map.entry("patchForObject", "PATCH"), Map.entry("delete", "DELETE"),
            Map.entry("exchange", ""), Map.entry("execute", ""));
    private static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete", "head", "options");
    private static final Map<String, String> MAPPINGS = Map.of("GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "PatchMapping", "PATCH", "DeleteMapping", "DELETE", "RequestMapping", "");
    private static final Map<String, String> EXCHANGES = Map.of("GetExchange", "GET", "PostExchange", "POST", "PutExchange", "PUT",
            "PatchExchange", "PATCH", "DeleteExchange", "DELETE", "HttpExchange", "");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final Pattern URL = Pattern.compile("^(https?://[^/]*)(.*)$");
    /** Marks a part of a URI the code does not know (a variable, a property without a value). */
    private static final String UNKNOWN = "\u0000";

    public static ServiceSurface of(Analysis a, ScanResult r) {
        Props props = new Props(a.springProperties, SpringProperties.profileValues(a.project.root()));
        String app = props.get("spring.application.name");
        app = app == null ? null : placeholders(props, app);
        return new ServiceSurface(new Calls(a, r, props).find(), entries(a, r, props), MetricsMap.counted(a),
                app == null || app.contains(UNKNOWN) || app.isBlank() ? null : app.strip());
    }

    /**
     * The service's properties (#128): the base configuration, else a value only profile files or profile documents set
     * when they agree on it (a local one, {@code localhost} or a loopback address, left out when another remains).
     */
    record Props(Map<String, String> base, Map<String, List<String>> profiles) {
        String get(String key) {
            String v = base.get(key);
            if (v != null) {
                return v;
            }
            List<String> values = profiles.getOrDefault(key, List.of());
            if (values.size() > 1) {
                List<String> remote = values.stream().filter(x -> !LOCAL.matcher(x).find()).toList();
                values = remote.isEmpty() ? values : remote;
            }
            return values.size() == 1 ? values.getFirst() : null;
        }
    }

    private static final Pattern LOCAL = Pattern.compile("(?i)^(?:[a-z][a-z0-9+.-]*://)?(?:[^@/]*@)?(?:localhost|127\\.[0-9.]+|0\\.0\\.0\\.0|\\[::1])(?:[:/]|$)");

    // ---- entrypoints ------------------------------------------------------------------------------------------------

    private static List<Entry> entries(Analysis a, ScanResult r, Props props) {
        List<Entry> out = new ArrayList<>();
        for (Entrypoint ep : a.entrypoints) {
            MethodInfo m = ep.method();
            String file = m.file().relPath();
            Break b = ep.observedByFramework() ? null : entryBreak(ep, r, file, m.line());
            switch (ep.kind()) {
                case HTTP -> {
                    for (String label : ep.labels()) {
                        String[] vp = label.split(" ", 2);
                        String verb = vp.length == 2 && Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(vp[0]) ? vp[0] : null;
                        out.add(new Entry("http", verb, vp.length == 2 ? vp[1] : vp[0], label, m.label(), file, m.line(), b));
                    }
                }
                case KAFKA, RABBIT, JMS -> {
                    String kind = ep.kind().name().toLowerCase(Locale.ROOT);
                    for (String dest : destinations(a, m, ep.kind(), props)) {
                        out.add(new Entry(kind, null, dest, ep.label(), m.label(), file, m.line(), b));
                    }
                }
                default -> {
                }
            }
        }
        out.sort(Comparator.comparing(Entry::file).thenComparingInt(Entry::line).thenComparing(Entry::target));
        return out;
    }

    private static Break entryBreak(Entrypoint ep, ScanResult r, String file, int line) {
        String what = switch (ep.kind()) {
            case KAFKA -> "the listener is not observed: it does not continue the record's trace";
            case RABBIT -> "the listener is not observed: it does not continue the message's trace";
            default -> "the entrypoint is not observed: it starts a trace of its own";
        };
        for (String rule : List.of(Rules.ENTRYPOINT_NOT_OBSERVED, Rules.KAFKA_OBSERVATION_DISABLED, Rules.RABBIT_OBSERVATION_DISABLED)) {
            for (WorkOrder o : r.orders()) {
                if (o.rule().equals(rule) && at(o, file, line)) {
                    return new Break(what, rule, o.id(), o.suggestion() != null ? o.suggestion() : o.title());
                }
            }
        }
        String fix = switch (ep.kind()) {
            case KAFKA -> "spring.kafka.listener.observation-enabled: true (not for batch listeners)";
            case RABBIT -> "spring.rabbitmq.listener.<simple|direct>.observation-enabled: true";
            default -> "observe the entrypoint";
        };
        return new Break(what, null, null, fix);
    }

    private static boolean at(WorkOrder o, String file, int line) {
        return file.equals(o.file()) && o.line() == line
                || o.locations() != null && o.locations().stream().anyMatch(l -> file.equals(l.file()) && l.line() == line);
    }

    /** The topics / queues / destinations a listener's annotation names, with property placeholders resolved. */
    private static List<String> destinations(Analysis a, MethodInfo m, Entrypoint.Kind kind, Props props) {
        String annotation = switch (kind) {
            case KAFKA -> "KafkaListener";
            case RABBIT -> "RabbitListener";
            default -> "JmsListener";
        };
        String[] attributes = switch (kind) {
            case KAFKA -> new String[]{"topics", "value"};
            case RABBIT -> new String[]{"queues", "value"};
            default -> new String[]{"destination"};
        };
        List<String> out = new ArrayList<>();
        List<J.Annotation> annotations = new ArrayList<>(m.decl().getLeadingAnnotations());
        annotations.addAll(m.owner().decl().getLeadingAnnotations());   // a class-level @KafkaListener with @KafkaHandler methods
        for (J.Annotation an : annotations) {
            if (!an.getSimpleName().equals(annotation)) {
                continue;
            }
            Annotations.attribute(an, attributes).ifPresent(e -> {
                List<Expression> values = e instanceof J.NewArray na ? na.getInitializer() == null ? List.of() : na.getInitializer() : List.of(e);
                for (Expression v : values) {
                    String s = v instanceof J.Empty ? null : Constants.concatenated(a.index, v);
                    String resolved = s == null ? null : placeholders(props, s);
                    if (resolved != null && !resolved.contains(UNKNOWN)) {
                        out.add(resolved);
                    }
                }
            });
            break;
        }
        return out;
    }

    /**
     * {@code ${app.topic:orders}} → the property's value in the service's application.yml (else a profile's, see
     * {@link Props}), else its default; a value that holds placeholders itself ({@code ${BILLING_URL:http://billing}})
     * is resolved too.
     */
    static String placeholders(Props props, String s) {
        return placeholders(props, s, 0);
    }

    private static String placeholders(Props props, String s, int depth) {
        Matcher pm = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (pm.find()) {
            String v = props.get(pm.group(1).strip());
            v = v != null ? v : pm.group(2) != null ? pm.group(2) : UNKNOWN;
            if (v.contains("${")) {
                v = depth < 5 ? placeholders(props, v, depth + 1) : UNKNOWN;
            }
            pm.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        pm.appendTail(sb);
        return sb.toString();
    }

    // ---- outgoing calls ---------------------------------------------------------------------------------------------

    private static final class Calls {
        final Analysis a;
        final ScanResult r;
        final Props props;
        final List<Call> out = new ArrayList<>();

        Calls(Analysis a, ScanResult r, Props props) {
            this.a = a;
            this.r = r;
            this.props = props;
        }

        List<Call> find() {
            for (MethodInfo m : a.index.methods()) {
                if (m.decl().getBody() == null || m.owner().fqn().startsWith(a.supportPackage + ".")) {
                    continue;
                }
                new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                        return c;
                    }

                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                        call(m, mi);
                        return super.visitMethodInvocation(mi, p);
                    }
                }.visit(m.decl().getBody(), 0);
            }
            for (ClassInfo c : a.index.classes()) {
                if (c.isInterface()) {
                    declarative(c);
                }
            }
            out.sort(Comparator.comparing(Call::file).thenComparingInt(Call::line));
            return out;
        }

        private void call(MethodInfo m, J.MethodInvocation mi) {
            List<Expression> args = mi.getArguments().stream().filter(x -> !(x instanceof J.Empty)).toList();
            String owner = owner(m, mi);
            String name = mi.getSimpleName();
            int line = Pos.line(mi.getName());
            String file = m.file().relPath();
            if (owner == null || args.isEmpty()) {
                return;
            }
            if (name.equals("uri") && (owner.startsWith("org.springframework.web.client.RestClient")
                    || owner.startsWith("org.springframework.web.reactive.function.client.WebClient"))
                    && mi.getSelect() instanceof J.MethodInvocation verbCall) {
                String verb = VERBS.contains(verbCall.getSimpleName()) ? verbCall.getSimpleName().toUpperCase(Locale.ROOT)
                        : verbCall.getSimpleName().equals("method") ? httpMethod(verbCall.getArguments().getFirst()) : null;
                String client = owner.contains("RestClient") ? "RestClient" : "WebClient";
                http(m, mi, verb, args.getFirst(), client, root(verbCall.getSelect()), file, line);
            } else if (REST_TEMPLATE.containsKey(name) && (owner.endsWith("RestTemplate") || owner.endsWith("RestOperations"))) {
                String verb = REST_TEMPLATE.get(name);
                if (verb.isEmpty()) {
                    verb = args.size() > 1 ? httpMethod(args.get(1)) : null;
                }
                http(m, mi, verb, args.getFirst(), "RestTemplate", root(mi.getSelect()), file, line);
            } else if (name.equals("send") && (owner.endsWith("KafkaTemplate") || owner.endsWith("KafkaOperations"))) {
                Expression topic = args.getFirst() instanceof J.NewClass nc && nc.getArguments() != null && !nc.getArguments().isEmpty()
                        ? nc.getArguments().getFirst() : args.getFirst();
                String t = value(m, topic);
                if (t != null && !t.contains(UNKNOWN)) {
                    out.add(new Call("kafka", null, t, null, "KafkaTemplate", m.label(), file, line, kafkaBreak()));
                }
            } else if (Set.of("convertAndSend", "send").contains(name) && (owner.endsWith("RabbitTemplate")
                    || owner.endsWith("AmqpTemplate") || owner.endsWith("RabbitOperations"))) {
                // (routingKey, message) on the default exchange, or (exchange, routingKey, message): the routing key names the queue
                Expression key = args.size() >= 3 ? args.get(1) : args.size() == 2 ? args.getFirst() : null;
                String t = key == null ? null : value(m, key);
                if (t != null && !t.contains(UNKNOWN)) {
                    out.add(new Call("rabbit", null, t, null, "RabbitTemplate", m.label(), file, line, rabbitBreak()));
                }
            } else if (Set.of("convertAndSend", "send").contains(name) && (owner.endsWith("JmsTemplate") || owner.endsWith("JmsOperations"))
                    && args.size() == 2) {
                String t = value(m, args.getFirst());
                if (t != null && !t.contains(UNKNOWN)) {
                    // Spring Boot puts the ObservationRegistry on the JmsTemplate it configures (JmsAutoConfiguration)
                    out.add(new Call("jms", null, t, null, "JmsTemplate", m.label(), file, line, null));
                }
            }
        }

        /** The declaring type of the called method; for an unresolved call, the type its receiver field is declared with. */
        private String owner(MethodInfo m, J.MethodInvocation mi) {
            if (mi.getMethodType() != null) {
                return mi.getMethodType().getDeclaringType().getFullyQualifiedName();
            }
            if (mi.getSelect() instanceof J.Identifier id) {
                for (Statement s : m.owner().decl().getBody().getStatements()) {
                    if (s instanceof J.VariableDeclarations vd && vd.getTypeExpression() != null
                            && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(id.getSimpleName()))) {
                        String t = vd.getTypeExpression().printTrimmed().replaceAll("<.*$", "");
                        return t.substring(t.lastIndexOf('.') + 1);
                    }
                }
            }
            return null;
        }

        private static String httpMethod(Expression e) {
            String s = e == null ? "" : e.printTrimmed();
            String v = s.substring(s.lastIndexOf('.') + 1).replaceAll("\\W", "").toUpperCase(Locale.ROOT);
            return Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(v) ? v : null;
        }

        /** The start of a call chain: {@code restClient} in {@code restClient.get()}, {@code RestClient.create()}. */
        private static Expression root(Expression e) {
            while (e instanceof J.MethodInvocation mi && mi.getSelect() != null
                    && !(mi.getSelect() instanceof J.Identifier id && Character.isUpperCase(id.getSimpleName().charAt(0)))) {
                e = mi.getSelect();
            }
            return e;
        }

        private void http(MethodInfo m, J.MethodInvocation mi, String verb, Expression uri, String client, Expression receiver,
                          String file, int line) {
            String u = value(m, uri);
            if (u == null) {
                return;
            }
            if (!URL.matcher(u).matches() && !u.startsWith(UNKNOWN)) {
                String base = clientBase(m, receiver);   // a relative URI: the client's base URL in front (#128)
                if (base != null) {
                    u = base + "/" + u;
                }
            }
            String[] bp = basePath(u.replaceAll("(?<!:)/{2,}", "/"));
            if (bp == null) {
                return;
            }
            out.add(new Call("http", verb, bp[1], bp[0], client, m.label(), file, line, httpBreak(m, client, receiver)));
        }

        /**
         * Whether the client sends the trace: HTTP_CLIENT_UNTRACED reports a client built by hand where it is built. A
         * call through a field is untraced when its class builds that kind of client by hand, or when it gets the client
         * injected and a {@code @Bean} method of the service builds that kind by hand.
         */
        private Break httpBreak(MethodInfo m, String client, Expression receiver) {
            String file = m.file().relPath();
            List<WorkOrder> untraced = r.orders().stream().filter(o -> o.rule().equals(Rules.HTTP_CLIENT_UNTRACED)
                    && o.title() != null && o.title().startsWith(client)).toList();
            for (WorkOrder o : untraced) {
                if (file.equals(o.file())) {
                    return new Break(o.title(), o.rule(), o.id(), o.suggestion());
                }
            }
            String field = receiver instanceof J.Identifier id ? id.getSimpleName()
                    : receiver instanceof J.FieldAccess fa ? fa.getSimpleName() : null;
            boolean builtHere = field != null && Pattern.compile("\\b" + Pattern.quote(field) + "\\s*=\\s*[^;]*(build|create)\\(")
                    .matcher(m.file().text()).find();
            if (field == null || builtHere) {
                return null;
            }
            for (WorkOrder o : untraced) {
                MethodInfo builder = methodAt(o.file(), o.line());
                if (builder != null && builder.hasAnnotation("org.springframework.context.annotation.Bean")) {
                    return new Break(o.title() + " (the " + client + " bean of " + builder.label()
                            + ", if that is the one injected here)", o.rule(), o.id(), o.suggestion());
                }
            }
            return null;
        }

        private MethodInfo methodAt(String file, int line) {
            for (MethodInfo m : a.index.methods()) {
                if (m.file().relPath().equals(file) && Pos.line(m.decl()) <= line && m.decl().getBody() != null
                        && Pos.endLine(m.decl().getBody()) >= line) {
                    return m;
                }
            }
            return null;
        }

        private Break kafkaBreak() {
            if ("true".equals(a.springProperties.get("spring.kafka.template.observation-enabled"))) {
                return null;
            }
            WorkOrder o = r.orders().stream().filter(x -> x.rule().equals(Rules.KAFKA_OBSERVATION_DISABLED)
                    && x.title() != null && x.title().contains("template")).findFirst().orElse(null);
            return new Break("KafkaTemplate without observation: the record carries no trace", Rules.KAFKA_OBSERVATION_DISABLED,
                    o == null ? null : o.id(), "spring.kafka.template.observation-enabled: true");
        }

        private Break rabbitBreak() {
            if ("true".equals(a.springProperties.get("spring.rabbitmq.template.observation-enabled"))) {
                return null;
            }
            return new Break("RabbitTemplate without observation: the message carries no trace", null, null,
                    "spring.rabbitmq.template.observation-enabled: true");
        }

        /** {@code @FeignClient} and HTTP interface ({@code @HttpExchange}) methods: each one a call. */
        private void declarative(ClassInfo c) {
            J.Annotation feign = annotation(c.decl().getLeadingAnnotations(), "FeignClient");
            J.Annotation exchange = annotation(c.decl().getLeadingAnnotations(), "HttpExchange");
            boolean exchangeMethods = c.decl().getBody().getStatements().stream().anyMatch(s -> s instanceof J.MethodDeclaration md
                    && md.getLeadingAnnotations().stream().anyMatch(an -> EXCHANGES.containsKey(an.getSimpleName())));
            if (feign == null && exchange == null && !exchangeMethods) {
                return;
            }
            Map<String, String> verbs = feign != null ? MAPPINGS : EXCHANGES;
            String prefix = "";
            String base = null;
            if (feign != null) {
                prefix = Annotations.attribute(feign, "path").map(e -> literal(c, e)).orElse("");
                base = Annotations.attribute(feign, "url").map(e -> literal(c, e)).orElse(null);
                if (base == null) {   // no url: the load balancer resolves the client's name, a service id (#128)
                    String id = Annotations.attribute(feign, "name", "value").map(e -> literal(c, e)).orElse(null);
                    base = id == null || id.contains(UNKNOWN) || id.isBlank() ? null : "http://" + id.strip();
                } else if (!base.contains(UNKNOWN) && !base.contains("://")) {
                    base = "http://" + base;   // url = "billing:8080"
                }
            } else if (exchange != null) {
                prefix = Annotations.attribute(exchange, "value", "url").map(e -> literal(c, e)).orElse("");
            }
            if (prefix == null) {
                prefix = UNKNOWN;
            }
            String client = feign != null ? "OpenFeign" : "HTTP interface";
            Break b = declarativeBreak(c, feign != null);
            for (MethodInfo m : a.index.methodsOf(c.fqn())) {
                for (J.Annotation an : m.decl().getLeadingAnnotations()) {
                    if (!verbs.containsKey(an.getSimpleName())) {
                        continue;
                    }
                    String verb = verbs.get(an.getSimpleName());
                    if (verb.isEmpty()) {
                        verb = Annotations.attribute(an, "method").map(Calls::httpMethod).orElse(null);
                    }
                    String path = Annotations.attribute(an, "value", "path", "url").map(e -> literal(c, e)).orElse("");
                    if (path == null) {
                        continue;
                    }
                    String full = (base == null || base.contains(UNKNOWN) ? UNKNOWN : base) + "/" + prefix + "/" + path;
                    String[] bp = basePath(full.replaceAll("(?<!:)/{2,}", "/"));
                    if (bp != null) {
                        out.add(new Call("http", verb, bp[1], bp[0], client, m.label(), c.file().relPath(), Pos.line(an), b));
                    }
                    break;
                }
            }
        }

        /** OpenFeign without feign-micrometer (HTTP_CLIENT_UNTRACED lists every client); an HTTP interface backed by a client built by hand. */
        private Break declarativeBreak(ClassInfo c, boolean feign) {
            String file = c.file().relPath();
            for (WorkOrder o : r.orders()) {
                if (!o.rule().equals(Rules.HTTP_CLIENT_UNTRACED)) {
                    continue;
                }
                if (feign && at(o, file, Pos.line(c.decl()))) {
                    return new Break(o.title(), o.rule(), o.id(), o.suggestion());
                }
                if (!feign && o.file() != null) {
                    String builder = a.project.files().stream().filter(f -> f.relPath().equals(o.file())).map(f -> f.text())
                            .findFirst().orElse("");
                    if (builder.contains(c.simpleName() + ".class") && builder.contains("HttpServiceProxyFactory")) {
                        return new Break(o.title() + " (the client behind " + c.simpleName() + ")", o.rule(), o.id(), o.suggestion());
                    }
                }
            }
            return null;
        }

        private String literal(ClassInfo c, Expression e) {
            String s = Constants.concatenated(a.index, e);
            return s == null ? null : placeholders(props, s);
        }

        private static J.Annotation annotation(List<J.Annotation> annotations, String simpleName) {
            return annotations.stream().filter(an -> an.getSimpleName().equals(simpleName)).findFirst().orElse(null);
        }

        /**
         * A string the code builds: literals and constants, {@code @Value("${…}")} fields from application.yml,
         * {@code a + b} concatenations; a part it does not know becomes {@link #UNKNOWN}.
         */
        private String value(MethodInfo m, Expression e) {
            if (e instanceof J.Parentheses<?> p && p.getTree() instanceof Expression inner) {
                return value(m, inner);
            }
            if (e instanceof J.Binary b && b.getOperator() == J.Binary.Type.Addition) {
                String left = value(m, b.getLeft());
                String right = value(m, b.getRight());
                return left == null || right == null ? null : left + right;
            }
            String s = Constants.string(a.index, e);
            if (s != null) {
                return placeholders(props, s);
            }
            if (e instanceof J.Identifier id) {
                String injected = valueParameter(m.decl().getParameters(), id.getSimpleName());   // a @Bean method's parameter
                if (injected == null) {
                    injected = injectedValue(m.owner(), id.getSimpleName());
                }
                if (injected != null) {
                    return injected;
                }
            }
            return UNKNOWN;   // a variable, a call, a URI object: matched without it, or not at all when nothing else is known
        }

        /** A field set from {@code @Value("${orders.url}")} (on the field or a constructor parameter of the same name). */
        private String injectedValue(ClassInfo c, String name) {
            List<J.VariableDeclarations> decls = new ArrayList<>();
            for (Statement s : c.decl().getBody().getStatements()) {
                if (s instanceof J.VariableDeclarations vd) {
                    decls.add(vd);
                } else if (s instanceof J.MethodDeclaration md && md.isConstructor()) {
                    md.getParameters().stream().filter(p -> p instanceof J.VariableDeclarations)
                            .forEach(p -> decls.add((J.VariableDeclarations) p));
                }
            }
            return valueOf(decls, name);
        }

        private String valueParameter(List<Statement> parameters, String name) {
            return valueOf(parameters.stream().filter(p -> p instanceof J.VariableDeclarations)
                    .map(p -> (J.VariableDeclarations) p).toList(), name);
        }

        private String valueOf(List<J.VariableDeclarations> decls, String name) {
            for (J.VariableDeclarations vd : decls) {
                if (vd.getVariables().stream().noneMatch(v -> v.getSimpleName().equals(name))) {
                    continue;
                }
                for (J.Annotation an : vd.getLeadingAnnotations()) {
                    if (an.getSimpleName().equals("Value")) {
                        String v = Annotations.attribute(an, "value").map(x -> Constants.string(a.index, x)).orElse(null);
                        if (v != null) {
                            return placeholders(props, v);
                        }
                    }
                }
            }
            return null;
        }

        /**
         * The base URL of the client a call goes through (#128): {@code baseUrl(…)} (RestClient, WebClient) or
         * {@code rootUri(…)} (RestTemplateBuilder) where the receiver field is built, in its class; for an injected client,
         * the {@code @Bean} method of the service that builds it (the one named like the field, else the only one of that
         * client type). Null when none is known.
         */
        private String clientBase(MethodInfo m, Expression receiver) {
            String field = receiver instanceof J.Identifier id ? id.getSimpleName()
                    : receiver instanceof J.FieldAccess fa ? fa.getSimpleName() : null;
            if (field == null) {
                return receiver == null ? null : baseIn(m, receiver);   // RestClient.builder().baseUrl(…).build().get()…
            }
            ClassInfo c = m.owner();
            String type = null;
            boolean assigned = false;
            for (Statement s : c.decl().getBody().getStatements()) {
                if (s instanceof J.VariableDeclarations vd) {
                    for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                        if (v.getSimpleName().equals(field)) {
                            type = vd.getTypeExpression() == null ? null : simpleType(vd.getTypeExpression());
                            if (v.getInitializer() != null) {
                                assigned = true;
                                String b = baseIn(m, v.getInitializer());
                                if (b != null) {
                                    return b;
                                }
                            }
                        }
                    }
                }
            }
            for (MethodInfo other : a.index.methodsOf(c.fqn())) {
                if (other.decl().getBody() == null) {
                    continue;
                }
                List<Expression> values = new ArrayList<>();
                new JavaIsoVisitor<Integer>() {
                    @Override
                    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, Integer p) {
                        return cd;
                    }

                    @Override
                    public J.Assignment visitAssignment(J.Assignment as, Integer p) {
                        Expression v = as.getVariable();
                        String n = v instanceof J.Identifier id ? id.getSimpleName() : v instanceof J.FieldAccess fa ? fa.getSimpleName() : null;
                        if (field.equals(n)) {
                            values.add(as.getAssignment());
                        }
                        return super.visitAssignment(as, p);
                    }
                }.visit(other.decl().getBody(), 0);
                for (Expression v : values) {
                    assigned = true;
                    String b = baseIn(other, v);
                    if (b != null) {
                        return b;
                    }
                }
            }
            if (assigned || type == null) {
                return null;   // built in the class without a base URL
            }
            List<MethodInfo> beans = new ArrayList<>();
            for (MethodInfo bm : a.index.methods()) {
                if (bm.decl().getBody() != null && bm.hasAnnotation("org.springframework.context.annotation.Bean")
                        && bm.decl().getReturnTypeExpression() != null && simpleType(bm.decl().getReturnTypeExpression()).equals(type)) {
                    beans.add(bm);
                }
            }
            MethodInfo bean = beans.stream().filter(bm -> bm.decl().getSimpleName().equals(field)).findFirst()
                    .orElse(beans.size() == 1 ? beans.getFirst() : null);
            return bean == null ? null : baseIn(bean, bean.decl().getBody());
        }

        private static String simpleType(TypeTree t) {
            return t.printTrimmed().replaceAll("<.*$", "").replaceAll("^.*\\.", "");
        }

        /** The value of the first {@code baseUrl(…)} / {@code rootUri(…)} in {@code tree}, evaluated in {@code m}. */
        private String baseIn(MethodInfo m, J tree) {
            List<Expression> args = new ArrayList<>();
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    boolean create = mi.getSimpleName().equals("create") && mi.getSelect() instanceof J.Identifier id
                            && Set.of("RestClient", "WebClient").contains(id.getSimpleName());   // RestClient.create(baseUrl)
                    if (args.isEmpty() && (create || Set.of("baseUrl", "rootUri").contains(mi.getSimpleName()))
                            && !mi.getArguments().isEmpty() && !(mi.getArguments().getFirst() instanceof J.Empty)) {
                        args.add(mi.getArguments().getFirst());
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(tree, 0);
            if (args.isEmpty()) {
                return null;
            }
            String v = value(m, args.getFirst());
            return v == null || v.equals(UNKNOWN) ? null : v;
        }
    }

    /** {scheme://host or null, path}: the path without query; unknown parts as {@code {?}}; null when no path is known. */
    static String[] basePath(String uri) {
        String base = null;
        String rest = uri;
        Matcher um = URL.matcher(uri);
        if (um.matches() && !um.group(1).contains(UNKNOWN)) {
            base = um.group(1);
            rest = um.group(2);
        } else if (uri.startsWith(UNKNOWN)) {
            rest = uri.substring(UNKNOWN.length());   // the base from a variable or property: the path part only
        }
        int q = rest.indexOf('?');
        rest = q >= 0 ? rest.substring(0, q) : rest;
        rest = rest.replace(UNKNOWN, "{?}");
        if (rest.isEmpty() || rest.equals("{?}") || rest.startsWith("{?}") && !rest.startsWith("{?}/")) {
            return null;   // nothing of the path is known
        }
        rest = ("/" + rest).replaceAll("/{2,}", "/");
        if (rest.length() > 1 && rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        return new String[]{base, rest};
    }
}
