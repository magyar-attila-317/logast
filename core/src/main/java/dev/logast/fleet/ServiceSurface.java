package dev.logast.fleet;

import dev.logast.Analysis;
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
 */
public record ServiceSurface(List<Call> calls, List<Entry> entries, List<MetricsMap.Counted> counted) {
    public ServiceSurface {
        calls = calls == null ? List.of() : List.copyOf(calls);
        entries = entries == null ? List.of() : List.copyOf(entries);
        counted = counted == null ? List.of() : List.copyOf(counted);
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
     * @param base   scheme and host when the code has them literally; null when they come from a property, a variable or
     *               the client's base URL (then the call is matched on its path only)
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
        return new ServiceSurface(new Calls(a, r).find(), entries(a, r), MetricsMap.counted(a));
    }

    // ---- entrypoints ------------------------------------------------------------------------------------------------

    private static List<Entry> entries(Analysis a, ScanResult r) {
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
                    for (String dest : destinations(a, m, ep.kind())) {
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
    private static List<String> destinations(Analysis a, MethodInfo m, Entrypoint.Kind kind) {
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
                    String resolved = s == null ? null : placeholders(a, s);
                    if (resolved != null && !resolved.contains(UNKNOWN)) {
                        out.add(resolved);
                    }
                }
            });
            break;
        }
        return out;
    }

    /** {@code ${app.topic:orders}} → the property's value in the service's application.yml, else its default. */
    private static String placeholders(Analysis a, String s) {
        Matcher pm = PLACEHOLDER.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (pm.find()) {
            String v = a.springProperties.get(pm.group(1));
            v = v != null ? v : pm.group(2) != null ? pm.group(2) : UNKNOWN;
            pm.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        pm.appendTail(sb);
        return sb.toString();
    }

    // ---- outgoing calls ---------------------------------------------------------------------------------------------

    private static final class Calls {
        final Analysis a;
        final ScanResult r;
        final List<Call> out = new ArrayList<>();

        Calls(Analysis a, ScanResult r) {
            this.a = a;
            this.r = r;
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
            String[] bp = basePath(u);
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
            return s == null ? null : placeholders(a, s);
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
                return placeholders(a, s);
            }
            if (e instanceof J.Identifier id) {
                String injected = injectedValue(m.owner(), id.getSimpleName());
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
            for (J.VariableDeclarations vd : decls) {
                if (vd.getVariables().stream().noneMatch(v -> v.getSimpleName().equals(name))) {
                    continue;
                }
                for (J.Annotation an : vd.getLeadingAnnotations()) {
                    if (an.getSimpleName().equals("Value")) {
                        String v = Annotations.attribute(an, "value").map(x -> Constants.string(a.index, x)).orElse(null);
                        if (v != null) {
                            return placeholders(a, v);
                        }
                    }
                }
            }
            return null;
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
