package dev.logast.rules;

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

    ResilienceRules(RuleContext ctx) {
        this.ctx = ctx;
        this.clients = ctx.enabled(Rules.HTTP_CLIENT_NO_TIMEOUT);
        this.executors = ctx.enabled(Rules.UNBOUNDED_EXECUTOR);
        this.waits = ctx.enabled(Rules.BLOCKING_WAIT_UNBOUNDED);
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
        if (users.isEmpty()) {
            return;
        }
        String[] first = users.getFirst();
        if (!props.containsKey("spring.jdbc.template.query-timeout")) {
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
        boolean transactional = ctx.methods().stream().anyMatch(m -> m.hasAnnotation("org.springframework.transaction.annotation.Transactional")
                || m.hasAnnotation("jakarta.transaction.Transactional"));
        if (transactional && !props.containsKey("spring.transaction.default-timeout")) {
            ctx.order(Rules.DB_NO_TIMEOUT, "transaction-timeout").at(ServiceYaml.path(ctx), 1)
                    .title("Transactions without a timeout: spring.transaction.default-timeout has no default, so a transaction "
                            + "waiting on a lock or a slow statement holds its connection indefinitely")
                    .suggestion("Decide a default (spring.transaction.default-timeout) above the longest legitimate transaction, "
                            + "or set timeout = … on the @Transactional methods that need one. Not applied automatically: it "
                            + "applies to every @Transactional, batch jobs included.")
                    .basis("@Transactional methods exist and no Spring configuration file sets spring.transaction.default-timeout")
                    .doneWhen("spring.transaction.default-timeout is set in the Spring configuration");
        }
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
