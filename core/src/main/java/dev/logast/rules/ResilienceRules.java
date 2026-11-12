package dev.logast.rules;

import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * {@code resilience} pack: what turns a slow or failing dependency into an outage.
 * <ul>
 *   <li>HTTP_CLIENT_NO_TIMEOUT: Spring HTTP clients without connect/read timeouts (Boot sets none), JDK HttpClient
 *       without a connect timeout;</li>
 *   <li>KAFKA_NO_ERROR_HANDLER: listeners on Boot's default error handling: 9 immediate retries, then the record is
 *       logged and skipped;</li>
 *   <li>UNBOUNDED_EXECUTOR: thread pools with unbounded threads or queues (memory, latency under load);</li>
 *   <li>BLOCKING_WAIT_UNBOUNDED: {@code future.get()}, {@code join()}, {@code latch.await()} without a timeout on a
 *       request/message path: one stuck dependency holds the request thread forever.</li>
 * </ul>
 * Method bodies and field initializers are both scanned (executors and clients usually live in fields).
 */
final class ResilienceRules {
    private static final Set<Entrypoint.Kind> REQUEST_KINDS = EnumSet.of(Entrypoint.Kind.HTTP, Entrypoint.Kind.KAFKA,
            Entrypoint.Kind.JMS, Entrypoint.Kind.RABBIT, Entrypoint.Kind.WEBSOCKET, Entrypoint.Kind.EVENT, Entrypoint.Kind.CUSTOM);

    /** Where a construct sits: a method body, or a field initializer of {@code owner}. */
    private record Site(SourceFileInfo file, MethodInfo method, ClassInfo owner, String text) {
        OrderBuilder order(RuleContext ctx, String rule, int line, String anchor) {
            return method != null ? ctx.order(rule, method, line, anchor)
                    : ctx.order(rule, owner.fqn() + "|field|" + anchor).at(file.relPath(), line);
        }

        void context(RuleContext ctx, OrderBuilder b, J tree, String code) {
            if (method != null) {
                b.context(ctx.context(method, Pos.start(tree), Text.oneLine(code, 160), null));
            }
        }
    }

    private final RuleContext ctx;
    private final List<String[]> untimedClients = new ArrayList<>();   // file, line
    private final Set<String> seenStatements = new HashSet<>();         // a builder chain is one client
    private final boolean clients;
    private final boolean executors;
    private final boolean waits;

    ResilienceRules(RuleContext ctx) {
        this.ctx = ctx;
        this.clients = ctx.enabled(Rules.HTTP_CLIENT_NO_TIMEOUT);
        this.executors = ctx.enabled(Rules.UNBOUNDED_EXECUTOR);
        this.waits = ctx.enabled(Rules.BLOCKING_WAIT_UNBOUNDED);
    }

    void run() {
        if (!clients && !executors && !waits && !ctx.enabled(Rules.KAFKA_NO_ERROR_HANDLER)) {
            return;
        }
        Set<String> onRequestPath = new HashSet<>();
        for (Entrypoint ep : ctx.a.entrypoints) {
            if (REQUEST_KINDS.contains(ep.kind())) {
                onRequestPath.addAll(ctx.a.paths.reachableFrom(ep));
            }
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() != null) {
                SourceFileInfo f = m.file();
                scan(new Site(f, m, m.owner(), f.slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()))),
                        m.decl().getBody(), onRequestPath.contains(m.key()), new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
            }
        }
        for (ClassInfo c : ctx.methodsOwners()) {
            for (Statement s : c.decl().getBody().getStatements()) {
                if (s instanceof J.VariableDeclarations vd) {
                    for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                        if (v.getInitializer() != null) {
                            scan(new Site(c.file(), null, c, c.file().slice(Pos.start(vd), Pos.end(vd))), v.getInitializer(), false,
                                    new Cursor(new Cursor(null, Cursor.ROOT_VALUE), vd));
                        }
                    }
                }
            }
        }
        if (clients) {
            springClientTimeouts();
        }
        if (ctx.enabled(Rules.KAFKA_NO_ERROR_HANDLER)) {
            kafka();
        }
    }

    private void scan(Site site, J tree, boolean requestPath, Cursor root) {
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                JavaType.Method mt = mi.getMethodType();
                if (mt != null) {
                    String owner = mt.getDeclaringType().getFullyQualifiedName();
                    String name = mt.getName();
                    if (clients) {
                        clientSite(site, mi, owner, name, getCursor());
                    }
                    if (executors && owner.equals("java.util.concurrent.Executors")
                            && Set.of("newCachedThreadPool", "newFixedThreadPool", "newSingleThreadExecutor").contains(name)) {
                        executor(site, mi, name.equals("newCachedThreadPool")
                                ? "creates threads without limit: a burst of tasks becomes a burst of threads"
                                : "queues tasks without limit: under load the queue grows until memory runs out, and latency with it");
                    }
                    if (waits && requestPath && mi.getArguments().stream().allMatch(a -> a instanceof J.Empty) && unboundedWait(owner, name)) {
                        unboundedWaitOrder(site, mi, simpleName(owner) + "." + name + "()");
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass nc, Integer p) {
                if (executors && TypeUtils.isOfClassType(nc.getType(), "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor")
                        && !site.text().contains("setQueueCapacity")) {
                    executor(site, nc, "has an unbounded queue (queueCapacity defaults to Integer.MAX_VALUE), so maxPoolSize never takes effect");
                }
                if (executors && TypeUtils.isOfClassType(nc.getType(), "java.util.concurrent.ThreadPoolExecutor")
                        && nc.getArguments().stream().anyMatch(a -> a instanceof J.NewClass q
                        && TypeUtils.isOfClassType(q.getType(), "java.util.concurrent.LinkedBlockingQueue")
                        && q.getArguments().stream().allMatch(x -> x instanceof J.Empty))) {
                    executor(site, nc, "uses an unbounded LinkedBlockingQueue, so maximumPoolSize never takes effect");
                }
                return super.visitNewClass(nc, p);
            }
        }.visit(tree, 0, root);
    }

    private static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    private static boolean unboundedWait(String owner, String name) {
        return name.equals("get") && (owner.equals("java.util.concurrent.Future") || owner.equals("java.util.concurrent.CompletableFuture")
                || owner.equals("java.util.concurrent.FutureTask"))
                || name.equals("join") && (owner.equals("java.util.concurrent.CompletableFuture") || owner.equals("java.lang.Thread"))
                || name.equals("await") && owner.equals("java.util.concurrent.CountDownLatch")
                || name.equals("acquire") && owner.equals("java.util.concurrent.Semaphore");
    }

    // ---- HTTP client timeouts -------------------------------------------------------------------

    /** Records Spring clients built without local timeouts; reports JDK HttpClients without a connect timeout. */
    private void clientSite(Site site, J.MethodInvocation mi, String owner, String name, Cursor cursor) {
        boolean spring = (owner.equals("org.springframework.web.client.RestClient") && Set.of("builder", "create").contains(name))
                || (owner.equals("org.springframework.web.client.RestClient$Builder") && name.equals("build"))
                || (owner.equals("org.springframework.boot.restclient.RestTemplateBuilder") && name.equals("build"));
        boolean jdk = owner.equals("java.net.http.HttpClient") && Set.of("newHttpClient", "newBuilder").contains(name);
        if (!spring && !jdk) {
            return;
        }
        J stmt = statement(cursor);
        String statement = stmt == null ? "" : site.file().slice(Pos.start(stmt), Pos.end(stmt));
        if (stmt != null && !seenStatements.add(site.file().relPath() + ":" + Pos.start(stmt))) {
            return;
        }
        if (statement.contains("imeout") || statement.contains("requestFactory(") || statement.contains("RequestFactory")) {
            return; // timeouts (or a configured request factory) set right here
        }
        int line = stmt != null ? Pos.line(stmt) : Pos.line(mi.getName());
        if (spring) {
            untimedClients.add(new String[]{site.file().relPath(), String.valueOf(line)});
            return;
        }
        if (ctx.ignored(site.file(), line, Rules.HTTP_CLIENT_NO_TIMEOUT)) {
            return;
        }
        OrderBuilder b = site.order(ctx, Rules.HTTP_CLIENT_NO_TIMEOUT, line, "jdk" + line)
                .title("java.net.http.HttpClient without connectTimeout: a dependency that never answers the TCP handshake "
                        + "blocks the caller until the OS gives up (minutes)")
                .suggestion("HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), and HttpRequest...timeout(...) per "
                        + "request; or use an injected RestClient.Builder (spring.http.clients.* timeouts apply).");
        site.context(ctx, b, mi, statement);
    }

    /** The statement containing the cursor (the whole builder chain / declaration). */
    private static J statement(Cursor c) {
        Cursor cur = c;
        for (Cursor p = c.getParentTreeCursor(); p.getValue() instanceof J j && !(j instanceof J.Block)
                && !(j instanceof J.MethodDeclaration) && !(j instanceof J.ClassDeclaration); p = p.getParentTreeCursor()) {
            cur = p;
            if (j instanceof J.VariableDeclarations) {
                break;
            }
        }
        return cur.getValue() instanceof J j ? j : null;
    }

    /** One YAML order: Spring's builders apply spring.http.clients.* to every client they build. */
    private void springClientTimeouts() {
        Map<String, String> props = ctx.a.springProperties;
        boolean read = props.keySet().stream().anyMatch(k -> k.matches("spring\\.http\\.clients?\\..*read-timeout"));
        boolean connect = props.keySet().stream().anyMatch(k -> k.matches("spring\\.http\\.clients?\\..*connect-timeout"));
        if (untimedClients.isEmpty() || read && connect) {
            return;
        }
        var cfg = ctx.policy.resilience();
        String[] first = untimedClients.getFirst();
        String missing = !read && !connect ? "connect/read" : read ? "connect" : "read";
        OrderBuilder b = ctx.order(Rules.HTTP_CLIENT_NO_TIMEOUT, "http-timeouts").at(first[0], Integer.parseInt(first[1]))
                .title("HTTP clients without " + missing + " timeout: Spring Boot sets none, so a slow dependency holds "
                        + "request threads until they run out")
                .suggestion("Sets spring.http.clients.connect-timeout=" + cfg.httpConnectTimeout() + " / read-timeout="
                        + cfg.httpReadTimeout() + " (policy resilience.*) for every client built from Spring's builders; "
                        + "clients that need longer get their own requestFactory settings.");
        StringBuilder snippet = new StringBuilder("spring:\n  http:\n    clients:\n");
        if (!connect) {
            snippet.append("      connect-timeout: ").append(cfg.httpConnectTimeout()).append('\n');
        }
        if (!read) {
            snippet.append("      read-timeout: ").append(cfg.httpReadTimeout()).append('\n');
        }
        ServiceYaml.merge(ctx, b, snippet.toString());
        untimedClients.stream().skip(1).forEach(c -> b.location(c[0], Integer.parseInt(c[1]), Rules.HTTP_CLIENT_NO_TIMEOUT));
    }

    // ---- Kafka ----------------------------------------------------------------------------------

    private void kafka() {
        List<Entrypoint> listeners = ctx.a.entrypoints.stream().filter(ep -> ep.kind() == Entrypoint.Kind.KAFKA).toList();
        if (listeners.isEmpty()) {
            return;
        }
        for (ClassInfo c : ctx.a.index.classes()) {
            String text = c.file().text();
            if (text.contains("DefaultErrorHandler") || text.contains("CommonErrorHandler") || text.contains("DeadLetterPublishingRecoverer")
                    || text.contains("@RetryableTopic") || text.contains("setCommonErrorHandler")) {
                return;
            }
        }
        Entrypoint first = listeners.getFirst();
        if (ctx.ignored(first.method().file(), first.method().line(), Rules.KAFKA_NO_ERROR_HANDLER)) {
            return;
        }
        OrderBuilder b = ctx.order(Rules.KAFKA_NO_ERROR_HANDLER, "kafka-errors").at(first.method().file().relPath(), first.method().line())
                .title("Kafka listeners on the default error handling: a failing record is retried 9 times without delay, "
                        + "then logged and skipped (lost)")
                .suggestion("Declare a CommonErrorHandler bean (Spring Boot wires it into the listener containers): "
                        + "new DefaultErrorHandler(new DeadLetterPublishingRecoverer(kafkaTemplate), new ExponentialBackOff(1000, 2)) "
                        + "sends records that still fail to <topic>-dlt; or use @RetryableTopic per listener.");
        listeners.stream().skip(1).forEach(ep -> b.location(ep.method().file().relPath(), ep.method().line(), Rules.KAFKA_NO_ERROR_HANDLER));
    }

    // ---- reports --------------------------------------------------------------------------------

    private void executor(Site site, J tree, String why) {
        int line = tree instanceof J.MethodInvocation mi ? Pos.line(mi.getName()) : Pos.line(tree);
        if (ctx.ignored(site.file(), line, Rules.UNBOUNDED_EXECUTOR)) {
            return;
        }
        OrderBuilder b = site.order(ctx, Rules.UNBOUNDED_EXECUTOR, line, "executor" + line)
                .title(Text.oneLine(tree.printTrimmed(), 60) + " " + why)
                .suggestion("Bound it: new ThreadPoolExecutor(core, max, 60s, new ArrayBlockingQueue<>(capacity), rejection policy), "
                        + "or ThreadPoolTaskExecutor with setQueueCapacity(..); decide what happens when it is full "
                        + "(CallerRunsPolicy slows the producer down instead of dropping work).");
        site.context(ctx, b, tree, tree.printTrimmed());
    }

    private void unboundedWaitOrder(Site site, J.MethodInvocation mi, String what) {
        int line = Pos.line(mi.getName());
        if (ctx.ignored(site.file(), line, Rules.BLOCKING_WAIT_UNBOUNDED)) {
            return;
        }
        OrderBuilder b = site.order(ctx, Rules.BLOCKING_WAIT_UNBOUNDED, line, "wait" + line)
                .title(what + " without a timeout on a request path: if the work never finishes, the request thread waits forever")
                .suggestion("Wait with a bound: get(5, TimeUnit.SECONDS) / orTimeout(..) / await(5, SECONDS), and handle the "
                        + "TimeoutException (fail the request, or return a partial answer).");
        site.context(ctx, b, mi, mi.printTrimmed());
    }
}
