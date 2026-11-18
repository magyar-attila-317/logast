package dev.logast.rules;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
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

        /** The n-th finding of {@code kind} in this method (or among the class's field initialisers). */
        OrderBuilder orderNth(RuleContext ctx, String rule, int line, String kind) {
            return method != null ? ctx.orderNth(rule, method, line, kind)
                    : ctx.orderNth(rule, owner.fqn() + "|field", file.relPath(), line, kind);
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
    private final boolean jdbc;

    ResilienceRules(RuleContext ctx) {
        this.ctx = ctx;
        this.clients = ctx.enabled(Rules.HTTP_CLIENT_NO_TIMEOUT);
        this.executors = ctx.enabled(Rules.UNBOUNDED_EXECUTOR);
        this.waits = ctx.enabled(Rules.BLOCKING_WAIT_UNBOUNDED);
        this.jdbc = ctx.enabled(Rules.DB_NO_TIMEOUT);
    }

    void run() {
        if (ctx.enabled(Rules.DB_NO_TIMEOUT)) {
            dbTimeouts();
        }
        if (ctx.enabled(Rules.REMOTE_CALL_IN_TRANSACTION)) {
            remoteInTransaction();
        }
        if (ctx.enabled(Rules.SCHEDULED_WITHOUT_LOCK) && !ctx.policy.resilience().singleInstance()) {
            scheduledWithoutLock();
        }
        if (ctx.enabled(Rules.RETRY_INACTIVE)) {
            retryInactive();
        }
        if (executors) {
            bootTaskExecutor();
        }
        if (ctx.enabled(Rules.SCHEDULER_SINGLE_THREAD)) {
            singleThreadScheduler();
        }
        if (!clients && !executors && !waits && !jdbc && !ctx.enabled(Rules.KAFKA_NO_ERROR_HANDLER)) {
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
                    if (jdbc && owner.equals("org.springframework.jdbc.core.simple.JdbcClient") && name.equals("create")
                            && mi.getArguments().size() == 1 && !wrapsTemplate(mi.getArguments().getFirst())) {
                        handMadeJdbc(site, mi, "JdbcClient.create(..)");
                    }
                    if (waits && requestPath && mi.getArguments().stream().allMatch(a -> a instanceof J.Empty) && unboundedWait(owner, name)
                            && !timed(mi.getSelect()) && !completedBefore(mi, getCursor())) {
                        unboundedWaitOrder(site, mi, simpleName(owner) + "." + name + "()");
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass nc, Integer p) {
                if (clients && TypeUtils.isOfClassType(nc.getType(), "org.springframework.web.client.RestTemplate")
                        && (nc.getArguments() == null || nc.getArguments().stream().allMatch(a -> a instanceof J.Empty))) {
                    restTemplate(site, nc, getCursor());
                }
                if (executors && TypeUtils.isOfClassType(nc.getType(), "org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor")
                        && !site.text().contains("setQueueCapacity")) {
                    executor(site, nc, "has an unbounded queue (queueCapacity defaults to Integer.MAX_VALUE), so maxPoolSize never takes effect");
                }
                if (jdbc && (TypeUtils.isOfClassType(nc.getType(), "org.springframework.jdbc.core.JdbcTemplate")
                        || TypeUtils.isOfClassType(nc.getType(), "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate")
                        && nc.getArguments().size() == 1 && !wrapsTemplate(nc.getArguments().getFirst()))) {
                    handMadeJdbc(site, nc, "new " + simpleName(TypeUtils.asFullyQualified(nc.getType()).getFullyQualifiedName()) + "(..)");
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

    /** Stages that pass on how the stage before them ends, without a wait of their own (#82). */
    private static final Set<String> PASS_THROUGH = Set.of("thenApply", "thenAccept", "thenRun", "exceptionally", "handle",
            "whenComplete");

    private static boolean onCompletableFuture(J.MethodInvocation mi, Set<String> names) {
        return names.contains(mi.getSimpleName()) && mi.getMethodType() != null
                && TypeUtils.isOfClassType(mi.getMethodType().getDeclaringType(), "java.util.concurrent.CompletableFuture");
    }

    /**
     * #82: {@code receiver} ends within a time bound: an {@code orTimeout(..)} / {@code completeOnTimeout(..)} in its
     * chain, followed only by stages that pass the outcome on ({@code exceptionally}, {@code thenApply}, …).
     */
    private static boolean timed(Expression receiver) {
        while (receiver instanceof J.MethodInvocation mi) {
            if (onCompletableFuture(mi, Set.of("orTimeout", "completeOnTimeout"))) {
                return true;
            }
            if (!onCompletableFuture(mi, PASS_THROUGH)) {
                return false;
            }
            receiver = mi.getSelect();
        }
        return false;
    }

    /**
     * #82: a no-argument wait on a local future that a bounded wait has already completed: a statement before it in the
     * same or an enclosing block (so it ran on every path that reaches the wait) waits with a bound on that variable,
     * {@code CompletableFuture.allOf(a, b).get(5, SECONDS);} or {@code a.orTimeout(..).join();}, and the variable is
     * never reassigned in the method. Anything less direct (a try around the bounded wait, a branch) still counts as
     * unbounded.
     */
    private static boolean completedBefore(J.MethodInvocation wait, Cursor cursor) {
        String var = local(wait.getSelect());
        if (var == null) {
            return false;
        }
        J inner = wait;
        J.MethodDeclaration method = null;
        boolean found = false;
        for (Iterator<Object> it = cursor.getPath(); it.hasNext(); ) {
            Object o = it.next();
            if (o instanceof J.MethodDeclaration md) {
                method = md;
                break;
            }
            if (o instanceof J.ClassDeclaration) {
                return false;
            }
            if (!found && o instanceof J.Block b) {
                for (Statement s : b.getStatements()) {
                    if (s == inner) {
                        break;
                    }
                    found |= completes(s).contains(var);
                }
            }
            if (o instanceof J j) {
                inner = j;
            }
        }
        return found && method != null && !reassigned(method, var);
    }

    /** The local futures a statement completes with a bounded wait. */
    private static Set<String> completes(Statement s) {
        Set<String> out = new HashSet<>();
        List<Expression> calls = new ArrayList<>();
        if (s instanceof J.MethodInvocation mi) {
            calls.add(mi);
        } else if (s instanceof J.Assignment a) {
            calls.add(a.getAssignment());
        } else if (s instanceof J.VariableDeclarations vd) {
            vd.getVariables().forEach(v -> calls.add(v.getInitializer()));
        }
        for (Expression e : calls) {
            if (!(e instanceof J.MethodInvocation mi) || mi.getMethodType() == null
                    || !TypeUtils.isAssignableTo("java.util.concurrent.Future", mi.getMethodType().getDeclaringType())) {
                continue;
            }
            boolean noArgs = mi.getArguments().stream().allMatch(a -> a instanceof J.Empty);
            boolean bounded = mi.getSimpleName().equals("get") && mi.getArguments().size() == 2
                    || Set.of("get", "join").contains(mi.getSimpleName()) && noArgs && timed(mi.getSelect());
            if (!bounded) {
                continue;
            }
            // the future itself: orTimeout / completeOnTimeout complete it (they return this)
            Expression receiver = mi.getSelect();
            boolean defaulted = false;
            while (receiver instanceof J.MethodInvocation r && onCompletableFuture(r, Set.of("orTimeout", "completeOnTimeout"))) {
                defaulted |= r.getSimpleName().equals("completeOnTimeout");
                receiver = r.getSelect();
            }
            String var = local(receiver);
            if (var != null) {
                out.add(var);
            } else if (receiver instanceof J.MethodInvocation all && onCompletableFuture(all, Set.of("allOf")) && !defaulted) {
                // allOf(..) ends normally only once every one of its futures has (completeOnTimeout would end it anyway)
                all.getArguments().forEach(a -> {
                    String v = local(a);
                    if (v != null) {
                        out.add(v);
                    }
                });
            }
        }
        return out;
    }

    /** The name of a local variable or parameter, else null. */
    private static String local(J e) {
        return e instanceof J.Identifier id && id.getFieldType() != null && id.getFieldType().getOwner() instanceof JavaType.Method
                ? id.getSimpleName() : null;
    }

    private static boolean reassigned(J.MethodDeclaration method, String var) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Assignment visitAssignment(J.Assignment a, Integer p) {
                found[0] |= a.getVariable() instanceof J.Identifier id && id.getSimpleName().equals(var);
                return super.visitAssignment(a, p);
            }
        }.visit(method, 0);
        return found[0];
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
            String unconfigured = unconfiguredBuilder(mi, cursor);
            if (unconfigured == null) {
                untimedClients.add(new String[]{site.file().relPath(), String.valueOf(line)});
            } else {
                unconfiguredClient(site, mi, line, unconfigured, statement);
            }
            return;
        }
        if (ctx.ignored(site.file(), line, Rules.HTTP_CLIENT_NO_TIMEOUT)) {
            return;
        }
        OrderBuilder b = site.orderNth(ctx, Rules.HTTP_CLIENT_NO_TIMEOUT, line, "jdk")
                .title("java.net.http.HttpClient without connectTimeout: a dependency that never answers the TCP handshake "
                        + "blocks the caller until the OS gives up (minutes)")
                .suggestion("HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), and HttpRequest...timeout(...) per "
                        + "request; or use an injected RestClient.Builder (spring.http.clients.* timeouts apply).");
        site.context(ctx, b, mi, statement);
    }

    /**
     * What the client is built from when it is not a builder Spring Boot configured (null when it is). Boot applies
     * spring.http.clients.* only to the builders it auto-configures and injects; {@code RestClient.create()} and the static
     * {@code RestClient.builder()} get no auto-configuration (Spring Boot reference, "Calling REST Services",
     * docs.spring.io/spring-boot/reference/io/rest-client.html). So only a chain that starts at a field or a parameter
     * of the enclosing method (an injected builder) counts as configured (#48).
     */
    private static String unconfiguredBuilder(J.MethodInvocation mi, Cursor cursor) {
        J.MethodInvocation first = mi;
        while (first.getSelect() instanceof J.MethodInvocation inner) {
            first = inner;
        }
        if (first.getMethodType() != null && first.getMethodType().hasFlags(org.openrewrite.java.tree.Flag.Static)) {
            return simpleName(first.getMethodType().getDeclaringType().getFullyQualifiedName()).replace('$', '.') + "."
                    + first.getSimpleName() + "()";
        }
        J receiver = first.getSelect() instanceof J.FieldAccess fa && fa.getTarget() instanceof J.Identifier t
                && t.getSimpleName().equals("this") ? fa.getName() : first.getSelect();
        if (receiver instanceof J.Identifier id && id.getFieldType() != null) {
            if (id.getFieldType().getOwner() instanceof JavaType.FullyQualified) {
                return null;   // a field: set from an injected builder
            }
            J.MethodDeclaration method = cursor.firstEnclosing(J.MethodDeclaration.class);
            if (method != null && method.getParameters().stream().anyMatch(p -> p instanceof J.VariableDeclarations vd
                    && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(id.getSimpleName())))) {
                return null;   // a parameter: Spring injects the configured builder
            }
            return "a local builder";
        }
        return receiver instanceof J.NewClass nc && nc.getClazz() != null ? "new " + nc.getClazz() + "()"
                : "a builder Spring Boot does not provide";
    }

    /**
     * A client Spring Boot's spring.http.clients.* timeouts do not reach (#48): reported where it is built, and the
     * property, set or not, does not silence it.
     */
    private void unconfiguredClient(Site site, J tree, int line, String from, String statement) {
        if (ctx.ignored(site.file(), line, Rules.HTTP_CLIENT_NO_TIMEOUT)) {
            return;
        }
        OrderBuilder b = site.orderNth(ctx, Rules.HTTP_CLIENT_NO_TIMEOUT, line, "unconfigured")
                .title("HTTP client built from " + from + " without timeouts: spring.http.clients.* does not reach it "
                        + "(Spring Boot configures only the builders it injects), so a slow dependency holds the calling thread")
                .suggestion("Build it from the injected RestClient.Builder / RestTemplateBuilder (spring.http.clients.* "
                        + "timeouts apply), or give it a requestFactory with connect and read timeouts.");
        site.context(ctx, b, tree, statement);
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

    /** {@code new RestTemplate()}: no request factory, so no timeouts, and no Spring Boot configuration either (#48). */
    private void restTemplate(Site site, J.NewClass nc, Cursor cursor) {
        J stmt = statement(cursor);
        String statement = stmt == null ? "" : site.file().slice(Pos.start(stmt), Pos.end(stmt));
        if (stmt != null && !seenStatements.add(site.file().relPath() + ":" + Pos.start(stmt))) {
            return;
        }
        if (statement.contains("imeout") || statement.contains("RequestFactory")) {
            return;
        }
        unconfiguredClient(site, nc, stmt != null ? Pos.line(stmt) : Pos.line(nc), "new RestTemplate()", statement);
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

    // ---- database --------------------------------------------------------------------------------

    private static final List<String> JDBC_TYPES = List.of("org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations", "org.springframework.jdbc.core.simple.JdbcClient");

    /**
     * DB_NO_TIMEOUT: JdbcTemplate (and NamedParameterJdbcTemplate / JdbcClient, which Boot builds on it) without a query
     * timeout: spring.jdbc.template.query-timeout has no default, the driver's usually means none, so a hanging query
     * holds its connection and thread forever. AUTO, with a generous policy value. A transaction timeout
     * (spring.transaction.default-timeout, also no default) applies to every @Transactional: reported only.
     */
    private void dbTimeouts() {
        Map<String, String> props = ctx.a.springProperties;
        List<String[]> users = new ArrayList<>();
        for (ClassInfo c : ctx.methodsOwners()) {
            for (Statement s : c.decl().getBody().getStatements()) {
                if (s instanceof J.VariableDeclarations vd && JDBC_TYPES.stream().anyMatch(t -> TypeUtils.isAssignableTo(t, vd.getType()))) {
                    users.add(new String[]{c.file().relPath(), String.valueOf(Pos.line(vd))});
                }
            }
        }
        if (!users.isEmpty() && !props.containsKey("spring.jdbc.template.query-timeout")) {
            String[] first = users.getFirst();
            String timeout = ctx.policy.resilience().dbQueryTimeout();
            OrderBuilder b = ctx.order(Rules.DB_NO_TIMEOUT, "jdbc-query-timeout").at(first[0], Integer.parseInt(first[1]))
                    .title("JdbcTemplate without a query timeout: Spring Boot sets none and the driver's default is usually none, "
                            + "so a hanging query holds its connection and thread forever")
                    .suggestion("Sets spring.jdbc.template.query-timeout=" + timeout + " (policy resilience.dbQueryTimeout): "
                            + "generous on purpose, to stop \"forever\" without cutting off long batch queries; lower it where "
                            + "queries are known to be short.");
            ServiceYaml.merge(ctx, b, "spring:\n  jdbc:\n    template:\n      query-timeout: " + timeout + "\n");
            users.stream().skip(1).forEach(u -> b.location(u[0], Integer.parseInt(u[1]), Rules.DB_NO_TIMEOUT));
        }
        jpaQueryTimeout();
        // not tied to a JdbcTemplate: a JPA-only service's transactions have no timeout either (#85)
        List<MethodInfo> untimed = ctx.methods().stream().filter(m -> TRANSACTIONAL.stream().anyMatch(m::hasAnnotation))
                .filter(m -> !transactionTimeout(m)).toList();
        if (!untimed.isEmpty() && !props.containsKey("spring.transaction.default-timeout")) {
            ctx.order(Rules.DB_NO_TIMEOUT, "transaction-timeout").at(ServiceYaml.path(ctx), 1)
                    .title("Transactions without a timeout: spring.transaction.default-timeout has no default, so a transaction "
                            + "waiting on a lock or a slow statement holds its connection indefinitely")
                    .suggestion("Decide a default (spring.transaction.default-timeout) above the longest legitimate transaction, "
                            + "or set timeout = … on the @Transactional methods that need one. Not applied automatically: it "
                            + "applies to every @Transactional, batch jobs included.")
                    .basis("@Transactional methods without timeout = … exist (" + untimed.getFirst().label()
                            + (untimed.size() > 1 ? " and " + (untimed.size() - 1) + " more" : "")
                            + ") and no Spring configuration file sets spring.transaction.default-timeout")
                    .doneWhen("spring.transaction.default-timeout is set in the Spring configuration, or every @Transactional "
                            + "method (or its class) has a timeout");
        }
    }

    /** An expression that is itself a JdbcTemplate (the new one wraps it, timeout included): not a template of its own. */
    private static boolean wrapsTemplate(Expression arg) {
        return arg.getType() != null && JDBC_TYPES.stream().anyMatch(t -> TypeUtils.isAssignableTo(t, arg.getType()));
    }

    /**
     * DB_NO_TIMEOUT, a JdbcTemplate created by hand (#41): Spring Boot applies spring.jdbc.template.* only to the
     * JdbcTemplate it auto-configures and the NamedParameterJdbcTemplate / JdbcClient built on it (Spring Boot
     * reference, "SQL Databases", "Using JdbcTemplate" / "Using JdbcClient",
     * docs.spring.io/spring-boot/reference/data/sql.html). One made with {@code new} keeps the driver's default, usually
     * no timeout. Report: the property, set or not, does not reach it.
     */
    private void handMadeJdbc(Site site, J tree, String what) {
        int line = tree instanceof J.MethodInvocation mi ? Pos.line(mi.getName()) : Pos.line(tree);
        String scope = site.method() != null ? site.text() : site.file().text();
        if (scope.contains("setQueryTimeout") || ctx.ignored(site.file(), line, Rules.DB_NO_TIMEOUT)) {
            return;
        }
        OrderBuilder b = site.orderNth(ctx, Rules.DB_NO_TIMEOUT, line, "hand-made-jdbc")
                .title(what + " creates a JdbcTemplate of its own: spring.jdbc.template.query-timeout does not reach it "
                        + "(Spring Boot configures only the template it provides), so a hanging query holds its connection "
                        + "and thread forever")
                .suggestion("Inject Spring Boot's JdbcTemplate / NamedParameterJdbcTemplate / JdbcClient instead "
                        + "(spring.jdbc.template.* applies to it), or call setQueryTimeout(seconds) on this one.")
                .doneWhen("the template is injected instead of created here, or setQueryTimeout(..) is called "
                        + (site.method() != null ? "in the same method" : "in the same class"));
        site.context(ctx, b, tree, tree.printTrimmed());
    }

    /**
     * DB_NO_TIMEOUT for JPA (#41): Spring Boot passes spring.jpa.properties.* on "(the prefix is stripped before adding
     * them to the entity manager)", Spring Boot reference, "SQL Databases", "Creating and Dropping JPA Databases"
     * (docs.spring.io/spring-boot/reference/data/sql.html); Hibernate keeps jakarta.persistence.query.timeout
     * (milliseconds) from there as a default session property and sets it as the timeout hint of every query a session
     * creates (SessionFactoryOptionsBuilder#initializeDefaultSessionProperties, SessionImpl#applyQuerySettingsAndHints:
     * the same in Hibernate 6.2, 6.6 and 7.1, i.e. Spring Boot 3.1+ and 4.x). It has no default. AUTO, with the
     * policy's generous DB timeout. It does not cover {@code find()} or the statements a flush runs; the transaction
     * timeout (reported below) does.
     */
    private void jpaQueryTimeout() {
        if (property("spring.jpa.properties.jakarta.persistence.query.timeout")
                || property("spring.jpa.properties.javax.persistence.query.timeout")) {
            return;
        }
        List<ClassInfo> jpa = ctx.methodsOwners().stream().filter(this::usesJpa)
                .sorted(Comparator.comparing((ClassInfo c) -> c.file().relPath()).thenComparingInt(c -> Pos.line(c.decl().getName())))
                .toList();
        Long millis = millis(ctx.policy.resilience().dbQueryTimeout());
        if (jpa.isEmpty() || millis == null) {
            return;
        }
        ClassInfo first = jpa.getFirst();
        OrderBuilder b = ctx.order(Rules.DB_NO_TIMEOUT, "jpa-query-timeout").at(first.file().relPath(), Pos.line(first.decl().getName()))
                .title("JPA queries without a timeout: jakarta.persistence.query.timeout has no default, so a hanging query "
                        + "holds its connection and thread forever")
                .suggestion("Sets spring.jpa.properties.jakarta.persistence.query.timeout=" + millis + " (milliseconds, policy "
                        + "resilience.dbQueryTimeout): Hibernate applies it to every query a session creates. Generous on "
                        + "purpose; lower it where queries are known to be short.");
        ServiceYaml.merge(ctx, b, "spring:\n  jpa:\n    properties:\n      jakarta.persistence.query.timeout: " + millis + "\n");
        jpa.stream().skip(1).forEach(c -> b.location(c.file().relPath(), Pos.line(c.decl().getName()), Rules.DB_NO_TIMEOUT));
    }

    /** An @Entity, a class holding an EntityManager, or a Spring Data JPA repository. */
    private boolean usesJpa(ClassInfo c) {
        String text = c.file().text();
        boolean imported = text.contains("import jakarta.persistence.") || text.contains("import javax.persistence.");
        boolean entity = c.decl().getLeadingAnnotations().stream().anyMatch(a -> (Annotations.is(a, "jakarta.persistence.Entity")
                || Annotations.is(a, "javax.persistence.Entity"))
                && (imported || a.getType() != null && !(a.getType() instanceof JavaType.Unknown)));
        if (entity || c.type() != null && TypeUtils.isAssignableTo("org.springframework.data.jpa.repository.JpaRepository", c.type())) {
            return true;
        }
        return c.decl().getBody().getStatements().stream().anyMatch(s -> s instanceof J.VariableDeclarations vd
                && (TypeUtils.isOfClassType(vd.getType(), "jakarta.persistence.EntityManager")
                || imported && vd.getTypeExpression() != null && vd.getTypeExpression().printTrimmed().equals("EntityManager")));
    }

    /** A duration as Spring Boot writes it (5m, 30s, 500ms, PT5M; no unit = seconds) in milliseconds; null if unreadable. */
    static Long millis(String duration) {
        String d = duration.strip().toLowerCase(Locale.ROOT);
        try {
            if (d.startsWith("p")) {
                return java.time.Duration.parse(d.toUpperCase(Locale.ROOT)).toMillis();
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)?").matcher(d);
            if (!m.matches()) {
                return null;
            }
            long n = Long.parseLong(m.group(1));
            return switch (m.group(2) == null ? "s" : m.group(2)) {
                case "ms" -> n;
                case "m" -> n * 60_000;
                case "h" -> n * 3_600_000;
                case "d" -> n * 86_400_000;
                default -> n * 1000;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The method's Spring @Transactional (its own, else its class's) sets a timeout. jakarta.transaction.Transactional
     * has no timeout attribute.
     */
    private static boolean transactionTimeout(MethodInfo m) {
        J.Annotation a = m.decl().getLeadingAnnotations().stream().filter(x -> TRANSACTIONAL.stream().anyMatch(t -> Annotations.is(x, t)))
                .findFirst().orElse(m.owner().decl().getLeadingAnnotations().stream()
                        .filter(x -> TRANSACTIONAL.stream().anyMatch(t -> Annotations.is(x, t))).findFirst().orElse(null));
        return a != null && Annotations.is(a, TRANSACTIONAL.getFirst()) && Annotations.attribute(a, "timeout", "timeoutString")
                .map(e -> e.printTrimmed()).filter(t -> !t.equals("-1") && !t.endsWith("TIMEOUT_DEFAULT") && !t.equals("\"\"")).isPresent();
    }

    // ---- scheduled jobs on several instances ----------------------------------------------------

    /** Locks that keep a scheduled run to one instance (ShedLock; others by name). */
    private static final List<String> SCHEDULER_LOCKS = List.of("net.javacrumbs.shedlock.spring.annotation.SchedulerLock",
            "net.javacrumbs.shedlock.core.SchedulerLock");

    /**
     * SCHEDULED_WITHOUT_LOCK (report): @Scheduled methods without a lock run on every instance at the same time once the
     * service is scaled out (double processing, duplicate sends). One finding for all of them; policy
     * resilience.singleInstance: true says the service never runs more than once.
     */
    private void scheduledWithoutLock() {
        List<MethodInfo> unlocked = ctx.a.entrypoints.stream().filter(ep -> ep.kind() == Entrypoint.Kind.SCHEDULED)
                .map(Entrypoint::method)
                .filter(m -> SCHEDULER_LOCKS.stream().noneMatch(m::hasAnnotation))
                .filter(m -> !ctx.ignored(m.file(), m.line(), Rules.SCHEDULED_WITHOUT_LOCK))
                .sorted(Comparator.comparing((MethodInfo m) -> m.file().relPath()).thenComparingInt(MethodInfo::line)).toList();
        if (unlocked.isEmpty()) {
            return;
        }
        MethodInfo first = unlocked.getFirst();
        OrderBuilder b = ctx.order(Rules.SCHEDULED_WITHOUT_LOCK, "scheduled-lock").at(first.file().relPath(), first.line())
                .title(unlocked.size() + " @Scheduled job(s) without a lock: with more than one instance each runs on every "
                        + "instance at the same time (" + String.join(", ", unlocked.stream().map(MethodInfo::label).toList()) + ")")
                .suggestion("Add a lock that keeps a run to one instance (ShedLock: @SchedulerLock(name = …) with a LockProvider), "
                        + "or set resilience.singleInstance: true in logast.yaml if the service never runs more than once.")
                .doneWhen("every @Scheduled method has @SchedulerLock, or logast.yaml sets resilience.singleInstance: true");
        unlocked.stream().skip(1).forEach(m -> b.location(m.file().relPath(), m.line(), Rules.SCHEDULED_WITHOUT_LOCK));
    }

    // ---- remote calls inside a transaction -------------------------------------------------------

    private static final List<String> TRANSACTIONAL = List.of("org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");
    /** Owners of blocking HTTP calls (RestClient's nested specs included), and HTTP interfaces by annotation. */
    private static final List<String> HTTP_OWNERS = List.of("org.springframework.web.client.RestOperations",
            "org.springframework.web.client.RestClient", "java.net.http.HttpClient");
    private static final List<String> HTTP_INTERFACES = List.of("org.springframework.cloud.openfeign.FeignClient",
            "org.springframework.web.service.annotation.HttpExchange");

    /**
     * REMOTE_CALL_IN_TRANSACTION (report): an HTTP call reached, on the same thread, from inside a @Transactional
     * method. The transaction holds its database connection for as long as the remote side takes; under load the pool
     * runs dry. Reported once per outermost transaction, with the path to the call.
     */
    private void remoteInTransaction() {
        Set<String> transactional = new HashSet<>();
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() != null && TRANSACTIONAL.stream().anyMatch(m::hasAnnotation)) {
                transactional.add(m.key());
            }
        }
        for (MethodInfo m : ctx.methods()) {
            if (!transactional.contains(m.key()) || ctx.a.graph.in(m).stream().anyMatch(e -> transactional.contains(e.from().key())
                    && e.async() == dev.logast.graph.CallGraph.Async.NONE)) {
                continue;   // inside an outer transaction: reported there
            }
            List<String> path = remotePath(m);
            if (path == null || ctx.ignored(m.file(), m.line(), Rules.REMOTE_CALL_IN_TRANSACTION)) {
                continue;
            }
            ctx.order(Rules.REMOTE_CALL_IN_TRANSACTION, m, m.line(), "remote-in-tx")
                    .title(m.label() + " is @Transactional and calls a remote service inside the transaction: "
                            + String.join(" → ", path) + ". The database connection is held while the network call waits")
                    .suggestion("Move the call out of the transaction (before it, or after commit: TransactionSynchronization / "
                            + "@TransactionalEventListener / an outbox), or make the transaction only cover the database work.")
                    .doneWhen("no blocking HTTP call is reachable from it on the same thread (up to 4 calls deep)");
        }
    }

    /** The first blocking HTTP call reachable on the same thread (4 calls deep), as a path of method labels; else null. */
    private List<String> remotePath(MethodInfo root) {
        Deque<List<MethodInfo>> todo = new ArrayDeque<>(List.of(List.of(root)));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            List<MethodInfo> path = todo.poll();
            MethodInfo m = path.getLast();
            if (!seen.add(m.key())) {
                continue;
            }
            for (var site : m.facts().calls) {
                if (site.async()) {
                    continue;
                }
                JavaType.FullyQualified owner = site.type().getDeclaringType();
                String fqn = owner.getFullyQualifiedName();
                boolean http = HTTP_OWNERS.stream().anyMatch(h -> fqn.equals(h) || fqn.startsWith(h + "$") || fqn.startsWith(h + ".")
                        || TypeUtils.isAssignableTo(h, owner))
                        && !fqn.endsWith("$Builder") && !fqn.endsWith(".Builder")   // building a client calls nothing
                        && !site.type().getName().startsWith("mutate") && !site.type().getName().equals("builder")
                        && (fqn.startsWith("java.net.http") ? site.type().getName().equals("send") : true);
                ClassInfo iface = ctx.a.index.clazz(fqn);
                boolean httpInterface = iface != null && iface.decl().getLeadingAnnotations().stream()
                        .anyMatch(a -> HTTP_INTERFACES.stream().anyMatch(h -> dev.logast.model.Annotations.is(a, h)));
                if (http || httpInterface) {
                    List<String> labels = new ArrayList<>(path.stream().map(MethodInfo::label).toList());
                    labels.add(owner.getClassName() + "." + site.type().getName());
                    return labels;
                }
            }
            if (path.size() <= 4) {
                for (var e : ctx.a.graph.out(m)) {
                    if (e.async() == dev.logast.graph.CallGraph.Async.NONE) {
                        List<MethodInfo> next = new ArrayList<>(path);
                        next.add(e.to());
                        todo.add(next);
                    }
                }
            }
        }
        return null;
    }

    // ---- retry annotations that do nothing ------------------------------------------------------

    private static final String RESILIENCE_PKG = "org.springframework.resilience.annotation.";
    private static final String SPRING_RETRY_PKG = "org.springframework.retry.annotation.";
    private static final String ENABLE_RESILIENT = RESILIENCE_PKG + "EnableResilientMethods";
    private static final String ENABLE_RETRY = SPRING_RETRY_PKG + "EnableRetry";

    /** A method (or class) carrying a retry annotation: where it is reported. */
    private record RetrySite(SourceFileInfo file, int line, String label) {
    }

    /**
     * RETRY_INACTIVE (#90): Spring Framework 7's {@code @Retryable} / {@code @ConcurrencyLimit} are "metadata" until
     * {@code @EnableResilientMethods} (or a RetryAnnotationBeanPostProcessor / ConcurrencyLimitBeanPostProcessor bean)
     * turns their processing on (Framework reference, "Resilience Features",
     * docs.spring.io/spring-framework/reference/core/resilience.html); Spring Boot does not enable them. Spring Retry's
     * {@code @Retryable} / {@code @CircuitBreaker} need {@code @EnableRetry} on a configuration class (spring-retry
     * README, "Java Configuration for Retry Proxies"). Without it the method runs once and the first failure is final.
     * AUTO: the enabling annotation goes onto the @SpringBootApplication class (a configuration class); Spring Retry's
     * only when AOP is on the classpath (its declarative retry needs it, README "Additional Dependencies"). Else report.
     */
    private void retryInactive() {
        List<RetrySite> retry = new ArrayList<>();
        List<RetrySite> limit = new ArrayList<>();
        List<RetrySite> springRetry = new ArrayList<>();
        for (ClassInfo c : ctx.methodsOwners()) {
            collectRetry(c.file(), c.decl().getLeadingAnnotations(), Pos.line(c.decl().getName()), c.simpleName(),
                    retry, limit, springRetry);
        }
        for (MethodInfo m : ctx.methods()) {
            collectRetry(m.file(), m.decl().getLeadingAnnotations(), m.line(), m.label(), retry, limit, springRetry);
        }
        boolean resilientOn = annotatedAnywhere(ENABLE_RESILIENT);
        Set<String> missing = new TreeSet<>();
        List<RetrySite> sites = new ArrayList<>();
        List<String> what = new ArrayList<>();
        if (!retry.isEmpty() && !resilientOn && !mentioned("RetryAnnotationBeanPostProcessor")) {
            missing.add(ENABLE_RESILIENT);
            sites.addAll(retry);
            what.add("@Retryable");
        }
        if (!limit.isEmpty() && !resilientOn && !mentioned("ConcurrencyLimitBeanPostProcessor")) {
            missing.add(ENABLE_RESILIENT);
            sites.addAll(limit);
            what.add("@ConcurrencyLimit");
        }
        if (!springRetry.isEmpty() && !annotatedAnywhere(ENABLE_RETRY)) {
            missing.add(ENABLE_RETRY);
            sites.addAll(springRetry);
            what.add("Spring Retry's @Retryable");
        }
        sites.removeIf(s -> ctx.ignored(s.file(), s.line(), Rules.RETRY_INACTIVE));
        if (sites.isEmpty()) {
            return;
        }
        sites.sort(Comparator.comparing((RetrySite s) -> s.file().relPath()).thenComparingInt(RetrySite::line));
        String enablers = String.join(" and ", missing.stream().map(f -> "@" + simpleName(f)).toList());
        RetrySite first = sites.getFirst();
        OrderBuilder b = ctx.order(Rules.RETRY_INACTIVE, "retry-inactive").at(first.file().relPath(), first.line())
                .title(String.join(" / ", what) + " without " + enablers + ": the annotation is metadata only, so "
                        + String.join(", ", sites.stream().map(RetrySite::label).distinct().toList())
                        + " run once and the first failure is final")
                .basis("no class is annotated with " + enablers + (missing.contains(ENABLE_RESILIENT)
                        ? " and no source declares a RetryAnnotationBeanPostProcessor / ConcurrencyLimitBeanPostProcessor bean" : ""))
                .doneWhen("a class is annotated with " + enablers + (missing.contains(ENABLE_RESILIENT)
                        ? " (or the post-processor beans are declared)" : ""));
        sites.stream().skip(1).forEach(s -> b.location(s.file().relPath(), s.line(), Rules.RETRY_INACTIVE));
        ClassInfo app = ctx.a.index.classes().stream()
                .filter(c -> c.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, SPRING_BOOT_APPLICATION)))
                .min(Comparator.comparing(c -> c.file().relPath())).orElse(null);
        boolean aop = !missing.contains(ENABLE_RETRY) || ctx.a.project.probe().hasClass("org.aspectj.weaver.tools.PointcutParser");
        if (app == null || !aop) {
            b.suggestion("Add " + enablers + " to a @Configuration class"
                    + (aop ? "" : "; Spring Retry's annotations also need AOP at runtime (spring-boot-starter-aop on "
                    + "Spring Boot 3, spring-boot-starter-aspectj on 4), or move to Spring Framework 7's own @Retryable")
                    + ". Not applied automatically: " + (app == null ? "no @SpringBootApplication class found." : "AspectJ is not on the classpath."));
            return;
        }
        J.Annotation boot = app.decl().getLeadingAnnotations().stream().filter(a -> Annotations.is(a, SPRING_BOOT_APPLICATION))
                .findFirst().orElseThrow();
        SourceFileInfo f = app.file();
        int at = Pos.start(boot);
        String indent = Text.indentAt(f.text(), at);
        StringBuilder added = new StringBuilder();
        missing.forEach(fqn -> added.append('@').append(simpleName(fqn)).append('\n').append(indent));
        b.suggestion("Adds " + enablers + " to " + app.simpleName() + " (the @SpringBootApplication class): exactly what the "
                + "annotations ask for. Check the retry settings (maxRetries, delay, includes) now that they take effect.");
        b.edits(JavaEdits.imports(f, missing.toArray(String[]::new)));
        b.edit(dev.logast.orders.Edit.insert(f.relPath(), at, added.toString()));
    }

    private static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";

    private static void collectRetry(SourceFileInfo f, List<J.Annotation> annotations, int line, String label,
                                     List<RetrySite> retry, List<RetrySite> limit, List<RetrySite> springRetry) {
        for (J.Annotation a : annotations) {
            String fqn = annotationFqn(a, f);
            if (fqn == null) {
                continue;
            }
            RetrySite site = new RetrySite(f, line, label);
            switch (fqn) {
                case RESILIENCE_PKG + "Retryable" -> retry.add(site);
                case RESILIENCE_PKG + "ConcurrencyLimit" -> limit.add(site);
                case SPRING_RETRY_PKG + "Retryable", SPRING_RETRY_PKG + "CircuitBreaker" -> springRetry.add(site);
                default -> {
                }
            }
        }
    }

    /**
     * The annotation's fully qualified name: its resolved type, else (the library is not on the classpath) the package
     * the file imports it from. Both libraries name their annotation {@code @Retryable}, so the simple name is not enough.
     */
    private static String annotationFqn(J.Annotation a, SourceFileInfo f) {
        JavaType.FullyQualified t = TypeUtils.asFullyQualified(a.getType());
        if (t != null && !(a.getType() instanceof JavaType.Unknown)) {
            return t.getFullyQualifiedName();
        }
        String simple = a.getSimpleName();
        String written = a.getAnnotationType().printTrimmed();
        for (String pkg : List.of(RESILIENCE_PKG, SPRING_RETRY_PKG)) {
            if (written.equals(pkg + simple) || f.text().contains("import " + pkg + simple + ";")
                    || f.text().contains("import " + pkg + "*;")) {
                return pkg + simple;
            }
        }
        return null;
    }

    private boolean annotatedAnywhere(String fqn) {
        return ctx.a.index.classes().stream().anyMatch(c -> c.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, fqn)));
    }

    /** Some source file of the service names {@code word} (a bean type declared by hand). */
    private boolean mentioned(String word) {
        return ctx.a.index.classes().stream().anyMatch(c -> c.file().text().contains(word));
    }

    // ---- Spring Boot's own executor and scheduler -----------------------------------------------

    private static final String ASYNC = "org.springframework.scheduling.annotation.Async";

    /** The Spring configuration sets {@code key} (relaxed binding: kebab-case, camelCase). */
    private boolean property(String key) {
        String want = key.replace("-", "").toLowerCase(Locale.ROOT);
        // a map key may be written in brackets ("[jakarta.persistence.query.timeout]")
        return ctx.a.springProperties.keySet().stream()
                .anyMatch(k -> k.replaceAll("[-\\[\\]\"']", "").toLowerCase(Locale.ROOT).equals(want));
    }

    private boolean virtualThreads() {
        return ctx.a.springProperties.entrySet().stream().anyMatch(e -> e.getKey().replace("-", "").equalsIgnoreCase("spring.threads.virtual.enabled")
                && e.getValue().equalsIgnoreCase("true"));
    }

    /** A @Bean method of the service returns one of {@code types}. */
    private boolean declaresBean(String... types) {
        return ctx.methods().stream().filter(m -> m.decl().getLeadingAnnotations().stream()
                        .anyMatch(a -> Annotations.is(a, "org.springframework.context.annotation.Bean")))
                .map(m -> m.decl().getReturnTypeExpression() == null ? null : m.decl().getReturnTypeExpression().getType())
                .filter(Objects::nonNull)
                .anyMatch(t -> Arrays.stream(types).anyMatch(x -> TypeUtils.isAssignableTo(x, t)));
    }

    private boolean implemented(String type) {
        return ctx.methodsOwners().stream().anyMatch(c -> c.type() != null && TypeUtils.isAssignableTo(type, c.type()));
    }

    /**
     * UNBOUNDED_EXECUTOR, Boot's own executor (#90): with {@code @EnableAsync} and no Executor bean of the service's own,
     * {@code @Async} methods run on the auto-configured ThreadPoolTaskExecutor: "8 core threads" (Spring Boot reference,
     * "Task Execution and Scheduling", docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html,
     * same in 3.5 and 4.x) and spring.task.execution.pool.queue-capacity defaulting to Integer.MAX_VALUE ("An unbounded
     * capacity does not increase the pool and therefore ignores the 'max-size' property", application properties
     * appendix). The auto-configuration backs off for an Executor bean unless spring.task.execution.mode=force; with
     * spring.threads.virtual.enabled it is a SimpleAsyncTaskExecutor without a pool. Report: the bound is a decision.
     */
    private void bootTaskExecutor() {
        boolean forced = ctx.a.springProperties.entrySet().stream().anyMatch(e -> e.getKey().equals("spring.task.execution.mode")
                && e.getValue().equalsIgnoreCase("force"));
        if (property("spring.task.execution.pool.queue-capacity") || virtualThreads()
                || !annotatedAnywhere("org.springframework.scheduling.annotation.EnableAsync")
                || !forced && declaresBean("java.util.concurrent.Executor")
                || implemented("org.springframework.scheduling.annotation.AsyncConfigurer")) {
            return;
        }
        List<MethodInfo> async = ctx.methods().stream().filter(this::onBootExecutor)
                .filter(m -> !ctx.ignored(m.file(), m.line(), Rules.UNBOUNDED_EXECUTOR))
                .sorted(Comparator.comparing((MethodInfo m) -> m.file().relPath()).thenComparingInt(MethodInfo::line)).toList();
        if (async.isEmpty()) {
            return;
        }
        MethodInfo first = async.getFirst();
        OrderBuilder b = ctx.order(Rules.UNBOUNDED_EXECUTOR, "boot-task-executor").at(first.file().relPath(), first.line())
                .title("@Async methods run on Spring Boot's auto-configured executor, whose queue is unbounded "
                        + "(spring.task.execution.pool.queue-capacity defaults to Integer.MAX_VALUE): under load tasks pile up "
                        + "behind 8 threads until memory runs out, and max-size never takes effect ("
                        + String.join(", ", async.stream().map(MethodInfo::label).toList()) + ")")
                .suggestion("Bound it: spring.task.execution.pool.queue-capacity (with max-size above the 8 core threads), "
                        + "and decide what a full queue means for the callers (a TaskRejectedException, or a rejection "
                        + "policy on your own executor); or run @Async on virtual threads (spring.threads.virtual.enabled: true).")
                .basis("@EnableAsync is present, no Executor bean or AsyncConfigurer of the service's own replaces Spring "
                        + "Boot's executor, and the Spring configuration sets neither spring.task.execution.pool.queue-capacity "
                        + "nor spring.threads.virtual.enabled: true")
                .doneWhen("spring.task.execution.pool.queue-capacity or spring.threads.virtual.enabled: true is set in the "
                        + "Spring configuration, or the service declares its own Executor bean / AsyncConfigurer");
        async.stream().skip(1).forEach(m -> b.location(m.file().relPath(), m.line(), Rules.UNBOUNDED_EXECUTOR));
    }

    /** {@code @Async} (on the method or its class) without a qualifier, or naming Boot's executor bean. */
    private boolean onBootExecutor(MethodInfo m) {
        J.Annotation a = m.decl().getLeadingAnnotations().stream().filter(x -> Annotations.is(x, ASYNC)).findFirst()
                .orElse(m.owner().decl().getLeadingAnnotations().stream().filter(x -> Annotations.is(x, ASYNC)).findFirst().orElse(null));
        if (a == null || m.decl().getBody() == null) {
            return false;
        }
        String name = Annotations.stringAttribute(a, "value").orElse("");
        return name.isEmpty() || name.equals("applicationTaskExecutor") || name.equals("taskExecutor");
    }

    /**
     * SCHEDULER_SINGLE_THREAD (#90): "The ThreadPoolTaskScheduler uses one thread by default" (Spring Boot reference,
     * "Task Execution and Scheduling", 3.5 and 4.x; spring.task.scheduling.pool.size defaults to 1), so with two or more
     * @Scheduled jobs one slow run delays every other job. Boot's scheduler backs off for a TaskScheduler or
     * ScheduledExecutorService bean (TaskSchedulingConfigurations, 3.5 and 4.0), and with virtual threads it is a
     * SimpleAsyncTaskScheduler without a pool. Report: more threads let jobs run concurrently, a decision.
     */
    private void singleThreadScheduler() {
        if (property("spring.task.scheduling.pool.size") || virtualThreads()
                || !annotatedAnywhere("org.springframework.scheduling.annotation.EnableScheduling")
                || declaresBean("org.springframework.scheduling.TaskScheduler", "java.util.concurrent.ScheduledExecutorService")
                || implemented("org.springframework.scheduling.annotation.SchedulingConfigurer")) {
            return;
        }
        List<MethodInfo> jobs = ctx.a.entrypoints.stream().filter(ep -> ep.kind() == Entrypoint.Kind.SCHEDULED)
                .map(Entrypoint::method).distinct()
                .sorted(Comparator.comparing((MethodInfo m) -> m.file().relPath()).thenComparingInt(MethodInfo::line)).toList();
        if (jobs.size() < 2 || jobs.stream().allMatch(m -> ctx.ignored(m.file(), m.line(), Rules.SCHEDULER_SINGLE_THREAD))) {
            return;
        }
        jobs = jobs.stream().filter(m -> !ctx.ignored(m.file(), m.line(), Rules.SCHEDULER_SINGLE_THREAD)).toList();
        MethodInfo first = jobs.getFirst();
        OrderBuilder b = ctx.order(Rules.SCHEDULER_SINGLE_THREAD, "scheduler-pool").at(first.file().relPath(), first.line())
                .title(jobs.size() + " @Scheduled jobs share Spring Boot's scheduler, which has one thread by default: one slow "
                        + "or stuck run delays every other job (" + String.join(", ", jobs.stream().map(MethodInfo::label).toList()) + ")")
                .suggestion("Set spring.task.scheduling.pool.size to the number of jobs that may run at the same time (they "
                        + "then run concurrently: check that they do not share state), or run them on virtual threads "
                        + "(spring.threads.virtual.enabled: true).")
                .basis("@EnableScheduling is present, no TaskScheduler / ScheduledExecutorService bean or SchedulingConfigurer "
                        + "of the service's own replaces Spring Boot's scheduler, and the Spring configuration sets neither "
                        + "spring.task.scheduling.pool.size nor spring.threads.virtual.enabled: true")
                .doneWhen("spring.task.scheduling.pool.size or spring.threads.virtual.enabled: true is set in the Spring "
                        + "configuration, or the service declares its own TaskScheduler bean / SchedulingConfigurer");
        jobs.stream().skip(1).forEach(m -> b.location(m.file().relPath(), m.line(), Rules.SCHEDULER_SINGLE_THREAD));
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
                        + "sends records that still fail to <topic>-dlt; or use @RetryableTopic per listener.")
                .basis("no source file mentions DefaultErrorHandler, CommonErrorHandler, DeadLetterPublishingRecoverer, "
                        + "@RetryableTopic or setCommonErrorHandler")
                .doneWhen("a source file uses one of them (e.g. declares a DefaultErrorHandler bean)");
        listeners.stream().skip(1).forEach(ep -> b.location(ep.method().file().relPath(), ep.method().line(), Rules.KAFKA_NO_ERROR_HANDLER));
    }

    // ---- reports --------------------------------------------------------------------------------

    private void executor(Site site, J tree, String why) {
        int line = tree instanceof J.MethodInvocation mi ? Pos.line(mi.getName()) : Pos.line(tree);
        if (ctx.ignored(site.file(), line, Rules.UNBOUNDED_EXECUTOR)) {
            return;
        }
        OrderBuilder b = site.orderNth(ctx, Rules.UNBOUNDED_EXECUTOR, line, "executor")
                .title(Text.oneLine(tree.printTrimmed(), 60) + " " + why)
                .suggestion("Bound it: new ThreadPoolExecutor(core, max, 60s, new ArrayBlockingQueue<>(capacity), rejection policy), "
                        + "or ThreadPoolTaskExecutor with setQueueCapacity(..); decide what happens when it is full "
                        + "(CallerRunsPolicy slows the producer down instead of dropping work).")
                .doneWhen("the executor is not created by this Executors factory");
        site.context(ctx, b, tree, tree.printTrimmed());
    }

    private void unboundedWaitOrder(Site site, J.MethodInvocation mi, String what) {
        int line = Pos.line(mi.getName());
        if (ctx.ignored(site.file(), line, Rules.BLOCKING_WAIT_UNBOUNDED)) {
            return;
        }
        OrderBuilder b = site.orderNth(ctx, Rules.BLOCKING_WAIT_UNBOUNDED, line, "wait")
                .title(what + " without a timeout on a request path: if the work never finishes, the request thread waits forever")
                .suggestion("Wait with a bound: get(5, TimeUnit.SECONDS) / orTimeout(..) / await(5, SECONDS), and handle the "
                        + "TimeoutException (fail the request, or return a partial answer).")
                .doneWhen("the wait takes a timeout argument (get(5, TimeUnit.SECONDS), await(5, SECONDS), tryAcquire(..)), "
                        + "orTimeout(..) precedes it in the chain, or an earlier bounded wait on the same future "
                        + "(allOf(..).get(5, SECONDS)) runs first on every path");
        site.context(ctx, b, mi, mi.printTrimmed());
    }
}
