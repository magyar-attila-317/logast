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
import java.util.stream.Stream;

/**
 * {@code resilience} pack: what turns a slow or failing dependency into an outage.
 * <ul>
 *   <li>HTTP_CLIENT_NO_TIMEOUT: Spring HTTP clients without connect/read timeouts (Boot sets none), JDK HttpClient
 *       without a connect timeout;</li>
 *   <li>KAFKA_NO_ERROR_HANDLER: listeners on Boot's default error handling: 9 immediate retries, then the record is
 *       logged and skipped;</li>
 *   <li>RABBIT_NO_ERROR_HANDLER: RabbitMQ listeners on Boot's defaults: a failing message is requeued without end;</li>
 *   <li>RETRY_IN_TRANSACTION: a @Retryable @Transactional method joining a caller's transaction;</li>
 *   <li>UNBOUNDED_EXECUTOR: thread pools with unbounded threads or queues (memory, latency under load);</li>
 *   <li>BLOCKING_WAIT_UNBOUNDED: {@code future.get()}, {@code join()}, {@code latch.await()} without a timeout on a
 *       request/message path: one stuck dependency holds the request thread forever;</li>
 *   <li>SHUTDOWN_NOT_GRACEFUL, HEALTH_PROBE_MISCONFIGURED, READINESS_WITHOUT_DATABASE: rollouts that cut work off and
 *       traffic that reaches instances that cannot serve it (service-level: only with a @SpringBootApplication);</li>
 *   <li>CACHE_UNBOUNDED: Spring caches without a size bound;</li>
 *   <li>RETRY_SETTINGS_HARDCODED: retry attempts and delays written into @Retryable ({@link RetrySettingsRules});</li>
 *   <li>RETRY_RECOMMENDED, NESTED_RETRY: idempotent calls nothing retries, retries inside retries ({@link RetryCoverageRules}).</li>
 * </ul>
 * Method bodies and field initializers are both scanned (executors and clients usually live in fields).
 */
final class ResilienceRules {
    private static final Set<Entrypoint.Kind> REQUEST_KINDS = EnumSet.of(Entrypoint.Kind.HTTP, Entrypoint.Kind.KAFKA,
            Entrypoint.Kind.JMS, Entrypoint.Kind.RABBIT, Entrypoint.Kind.WEBSOCKET, Entrypoint.Kind.EVENT, Entrypoint.Kind.CUSTOM,
            Entrypoint.Kind.SQS, Entrypoint.Kind.STREAM);

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
    private final List<String[]> untimedClients = new ArrayList<>();   // file, line: RestClient / RestTemplate builders Boot injects
    private final List<String[]> untimedWebClients = new ArrayList<>(); // file, line: WebClient builders Boot injects
    private final Set<String> seenStatements = new HashSet<>();         // a builder chain is one client
    private int[] bootVersion;                                          // {major, minor}, read once
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
        if (ctx.enabled(Rules.SHUTDOWN_NOT_GRACEFUL)) {
            shutdown();
        }
        if (ctx.enabled(Rules.HEALTH_PROBE_MISCONFIGURED) || ctx.enabled(Rules.READINESS_WITHOUT_DATABASE)) {
            probes();
        }
        if (ctx.enabled(Rules.CACHE_UNBOUNDED)) {
            caches();
        }
        if (ctx.enabled(Rules.RABBIT_NO_ERROR_HANDLER)) {
            rabbit();
        }
        if (ctx.enabled(Rules.RETRY_IN_TRANSACTION)) {
            retryInTransaction();
        }
        if (ctx.enabled(Rules.RETRY_SETTINGS_HARDCODED)) {
            new RetrySettingsRules(ctx).run();
        }
        if (ctx.enabled(Rules.RETRY_RECOMMENDED) || ctx.enabled(Rules.NESTED_RETRY)) {
            new RetryCoverageRules(ctx).run();
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
                if (clients && apacheClient(mi, site.file())) {
                    apacheClientSite(site, mi, getCursor());
                }
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
                || (REST_TEMPLATE_BUILDERS.contains(owner) && name.equals("build"));
        boolean webClient = (owner.equals(WEB_CLIENT) && Set.of("builder", "create").contains(name))
                || (owner.equals(WEB_CLIENT + "$Builder") && name.equals("build"));
        boolean jdk = owner.equals("java.net.http.HttpClient") && Set.of("newHttpClient", "newBuilder").contains(name);
        if (!spring && !jdk && !webClient) {
            return;
        }
        J stmt = statement(cursor);
        String statement = stmt == null ? "" : site.file().slice(Pos.start(stmt), Pos.end(stmt));
        if (stmt != null && !seenStatements.add(site.file().relPath() + ":" + Pos.start(stmt))) {
            return;
        }
        if (statement.contains("imeout") || statement.contains("requestFactory(") || statement.contains("RequestFactory")
                || webClient && statement.contains("clientConnector(")) {
            return; // timeouts (or a configured request factory / connector) set right here
        }
        int line = stmt != null ? Pos.line(stmt) : Pos.line(mi.getName());
        if (webClient) {
            // #90 item 6: WebClient.create() / the static WebClient.builder() get no auto-configuration (Spring Boot
            // reference, "Calling REST Services"); a builder Spring injects is left to Spring Boot's settings
            String unconfigured = unconfiguredBuilder(mi, cursor);
            if (unconfigured == null) {
                untimedWebClients.add(new String[]{site.file().relPath(), String.valueOf(line)});   // Boot's builder: YAML
            } else {
                unconfiguredClient(site, mi, line, unconfigured, statement, true, "Build it from the injected WebClient.Builder "
                        + "(" + httpClientProperties(true) + " timeouts apply), or give it a clientConnector with a connect and a "
                        + "response timeout (Reactor Netty: HttpClient.create().option(CONNECT_TIMEOUT_MILLIS, ..)"
                        + ".responseTimeout(..)); Reactor Netty sets no response timeout by default.");
            }
            return;
        }
        if (spring) {
            String unconfigured = unconfiguredBuilder(mi, cursor);
            if (unconfigured == null) {
                untimedClients.add(new String[]{site.file().relPath(), String.valueOf(line)});
            } else {
                unconfiguredClient(site, mi, line, unconfigured, statement, false, springClientRemedy());
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
                        + "request; or use an injected RestClient.Builder (" + httpClientProperties(false) + " timeouts apply).");
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
    private void unconfiguredClient(Site site, J tree, int line, String from, String statement, boolean reactive, String remedy) {
        if (ctx.ignored(site.file(), line, Rules.HTTP_CLIENT_NO_TIMEOUT)) {
            return;
        }
        OrderBuilder b = site.orderNth(ctx, Rules.HTTP_CLIENT_NO_TIMEOUT, line, "unconfigured")
                .title("HTTP client built from " + from + " without timeouts: " + httpClientProperties(reactive) + " does not reach it "
                        + "(Spring Boot configures only the builders it injects), so a slow dependency holds the calling thread")
                .suggestion(remedy);
        site.context(ctx, b, tree, statement);
    }

    private String springClientRemedy() {
        return "Build it from the injected RestClient.Builder / RestTemplateBuilder (" + httpClientProperties(false)
                + " timeouts apply), or give it a requestFactory with connect and read timeouts.";
    }

    /**
     * Spring Boot's RestTemplateBuilder: org.springframework.boot.web.client in Boot 3, org.springframework.boot.restclient
     * from Boot 4.0, in the spring-boot-restclient module (API docs, docs.spring.io/spring-boot/3.5/api/java and /4.0/api/java)
     * (#98).
     */
    private static final Set<String> REST_TEMPLATE_BUILDERS = Set.of("org.springframework.boot.web.client.RestTemplateBuilder",
            "org.springframework.boot.restclient.RestTemplateBuilder");

    /** The service's Spring Boot {major, minor} (see {@link SetupRules#springBootVersion}), read once per scan. */
    private int[] bootVersion() {
        if (bootVersion == null) {
            bootVersion = SetupRules.springBootVersion(ctx);
        }
        return bootVersion;
    }

    /**
     * Spring Boot's HTTP client timeout properties as this Boot version names them, for advice (#98): spring.http.clients.*
     * from 4.0 (and for an unknown version); in 3.x spring.http.client.* for RestClient / RestTemplate and
     * spring.http.reactiveclient.* for WebClient (application properties appendix, 3.5 and 4.0).
     */
    private String httpClientProperties(boolean reactive) {
        int major = bootVersion()[0];
        return major == 0 || major >= 4 ? "spring.http.clients.*" : reactive ? "spring.http.reactiveclient.*" : "spring.http.client.*";
    }

    private static final String WEB_CLIENT = "org.springframework.web.reactive.function.client.WebClient";
    /** Apache HttpClient: 5.x (classic and async) and 4.x. */
    private static final List<String> APACHE_PACKAGES = List.of("org.apache.hc.client5.http.", "org.apache.http.impl.");
    private static final Set<String> APACHE_FACTORIES = Set.of("HttpClients", "HttpClientBuilder", "HttpAsyncClients",
            "HttpAsyncClientBuilder");

    /**
     * #90 item 6: {@code HttpClients.createDefault()} / {@code .custom()} / {@code HttpClientBuilder.create()} of Apache
     * HttpClient, resolved or (the library is not on logast's classpath) imported from its packages.
     */
    private static boolean apacheClient(J.MethodInvocation mi, SourceFileInfo f) {
        if (!Set.of("createDefault", "createSystem", "custom", "create").contains(mi.getSimpleName())
                || !(mi.getSelect() instanceof J.Identifier factory) || !APACHE_FACTORIES.contains(factory.getSimpleName())) {
            return false;
        }
        JavaType.Method mt = mi.getMethodType();
        if (mt != null && !(mt.getDeclaringType() instanceof JavaType.Unknown)) {
            String owner = mt.getDeclaringType().getFullyQualifiedName();
            return APACHE_PACKAGES.stream().anyMatch(owner::startsWith);
        }
        String text = f.text();
        return APACHE_PACKAGES.stream().anyMatch(pkg -> java.util.regex.Pattern.compile("(?m)^import\\s+"
                + java.util.regex.Pattern.quote(pkg) + "[\\w.]*\\b" + factory.getSimpleName() + ";").matcher(text).find());
    }

    /**
     * Apache HttpClient built by hand (#90 item 6): no Spring Boot setting reaches it, its socket/response timeout is
     * undefined by default (no timeout) and its connect timeout 3 minutes (httpclient5 ConnectionConfig /
     * RequestConfig). Reported unless the statement configures timeouts, a RequestConfig, ConnectionConfig or
     * connection manager.
     */
    private void apacheClientSite(Site site, J.MethodInvocation mi, Cursor cursor) {
        J stmt = statement(cursor);
        String statement = stmt == null ? "" : site.file().slice(Pos.start(stmt), Pos.end(stmt));
        if (stmt != null && !seenStatements.add(site.file().relPath() + ":" + Pos.start(stmt))) {
            return;
        }
        if (statement.contains("imeout") || statement.contains("RequestConfig") || statement.contains("ConnectionConfig")
                || statement.contains("ConnectionManager")) {
            return;
        }
        int line = stmt != null ? Pos.line(stmt) : Pos.line(mi.getName());
        if (ctx.ignored(site.file(), line, Rules.HTTP_CLIENT_NO_TIMEOUT)) {
            return;
        }
        String from = ((J.Identifier) mi.getSelect()).getSimpleName() + "." + mi.getSimpleName() + "()";
        OrderBuilder b = site.orderNth(ctx, Rules.HTTP_CLIENT_NO_TIMEOUT, line, "apache")
                .title("Apache HttpClient built with " + from + " without timeouts: its response (socket) timeout is "
                        + "undefined by default and its connect timeout 3 minutes, and no Spring Boot setting reaches it, so "
                        + "a dependency that stops answering holds the calling thread")
                .suggestion("Set the timeouts on the client: setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(..)"
                        + ".build()) and a ConnectionConfig with setConnectTimeout(..) / setSocketTimeout(..) on its "
                        + "connection manager; or call through Spring's injected RestClient.Builder (" + httpClientProperties(false) + " "
                        + "applies to the HttpComponents request factory it creates).")
                .doneWhen("the statement that builds the client sets a timeout, a RequestConfig, a ConnectionConfig or a "
                        + "connection manager");
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
        unconfiguredClient(site, nc, stmt != null ? Pos.line(stmt) : Pos.line(nc), "new RestTemplate()", statement, false,
                springClientRemedy());
    }

    /**
     * YAML orders: Spring Boot applies its HTTP client properties to every client built from the builders it injects
     * (Spring Boot reference, "Calling REST Services"). The names depend on the version (application properties
     * appendix): Boot 4 has spring.http.clients.* for RestClient, RestTemplate and WebClient alike; Boot 3.5 has
     * spring.http.client.* for RestClient / RestTemplate and spring.http.reactiveclient.* for WebClient; Boot 3.4 only
     * the former; earlier versions neither. An unknown version gets Boot 4's names (logast's default target). Where no
     * property exists the clients are reported instead.
     */
    private void springClientTimeouts() {
        int[] boot = bootVersion();
        if (boot[0] == 3 || boot[0] > 0 && boot[0] < 3) {
            boolean three = boot[0] == 3;
            clientTimeouts(untimedClients, "client", "http-timeouts", "HTTP clients",
                    three && boot[1] >= 4 ? null : "Spring Boot " + boot[0] + "." + boot[1] + " has no property for them "
                            + "(spring.http.client.* came with 3.4): build them with timeouts (RestTemplateBuilder.connectTimeout(..) "
                            + "/ readTimeout(..), or a requestFactory with connect and read timeouts on the RestClient.Builder).");
            clientTimeouts(untimedWebClients, "reactiveclient", "http-timeouts-reactive", "WebClients",
                    three && boot[1] >= 5 ? null : "Spring Boot " + boot[0] + "." + boot[1] + " has no property for them "
                            + "(spring.http.reactiveclient.* came with 3.5): declare a WebClientCustomizer that sets a "
                            + "clientConnector with a connect and a response timeout (Reactor Netty: HttpClient.create()"
                            + ".option(CONNECT_TIMEOUT_MILLIS, ..).responseTimeout(..)).");
            return;
        }
        List<String[]> all = new ArrayList<>(untimedClients);
        all.addAll(untimedWebClients);
        clientTimeouts(all, "clients", "http-timeouts", "HTTP clients", null);
    }

    /**
     * One order for {@code clients}: sets spring.http.{@code group}.connect-timeout / read-timeout, or, when this Spring
     * Boot version has no such property ({@code noProperty} says what to do instead), reports them.
     */
    private void clientTimeouts(List<String[]> clients, String group, String anchor, String what, String noProperty) {
        String prefix = "spring\\.http\\." + java.util.regex.Pattern.quote(group) + "\\.";
        Map<String, String> props = ctx.a.springProperties;
        boolean read = props.keySet().stream().anyMatch(k -> k.matches(prefix + ".*read-?timeout"));
        boolean connect = props.keySet().stream().anyMatch(k -> k.matches(prefix + ".*connect-?timeout"));
        if (clients.isEmpty() || noProperty == null && read && connect) {
            return;
        }
        var cfg = ctx.policy.resilience();
        String[] first = clients.getFirst();
        String missing = noProperty != null || !read && !connect ? "connect/read" : read ? "connect" : "read";
        OrderBuilder b = ctx.order(Rules.HTTP_CLIENT_NO_TIMEOUT, anchor).at(first[0], Integer.parseInt(first[1]))
                .title(what + " without " + missing + " timeout: Spring Boot sets none, so a slow dependency holds "
                        + "request threads until they run out");
        clients.stream().skip(1).forEach(c -> b.location(c[0], Integer.parseInt(c[1]), Rules.HTTP_CLIENT_NO_TIMEOUT));
        if (noProperty != null) {
            b.suggestion(noProperty);
            return;
        }
        b.suggestion("Sets spring.http." + group + ".connect-timeout=" + cfg.httpConnectTimeout() + " / read-timeout="
                + cfg.httpReadTimeout() + " (policy resilience.*) for every client built from Spring's builders; "
                + "clients that need longer get their own requestFactory settings.");
        StringBuilder snippet = new StringBuilder("spring:\n  http:\n    " + group + ":\n");
        if (!connect) {
            snippet.append("      connect-timeout: ").append(cfg.httpConnectTimeout()).append('\n');
        }
        if (!read) {
            snippet.append("      read-timeout: ").append(cfg.httpReadTimeout()).append('\n');
        }
        ServiceYaml.merge(ctx, b, snippet.toString());
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

    static final String RESILIENCE_PKG = "org.springframework.resilience.annotation.";
    static final String SPRING_RETRY_PKG = "org.springframework.retry.annotation.";
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

    static final String SPRING_BOOT_APPLICATION = "org.springframework.boot.autoconfigure.SpringBootApplication";

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
    static String annotationFqn(J.Annotation a, SourceFileInfo f) {
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

    // ---- rollout and readiness (#29, #30) ------------------------------------------------------

    /** The service's @SpringBootApplication class (the first, sorted by path), or null for a library. */
    private ClassInfo bootApplication() {
        return ctx.a.index.classes().stream()
                .filter(c -> c.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, SPRING_BOOT_APPLICATION)))
                .min(Comparator.comparing(c -> c.file().relPath())).orElse(null);
    }

    /** The value the Spring configuration sets for {@code key} (relaxed binding), or null. */
    private String value(String key) {
        String want = key.replace("-", "").toLowerCase(Locale.ROOT);
        return ctx.a.springProperties.entrySet().stream()
                .filter(e -> e.getKey().replace("-", "").toLowerCase(Locale.ROOT).equals(want))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    /** The comma-separated or list values of {@code key} ({@code key: a,b} or {@code key: [a, b]}), as written. */
    private List<String> values(String key) {
        String want = key.replace("-", "").toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        ctx.a.springProperties.forEach((k, v) -> {
            String norm = k.replace("-", "").toLowerCase(Locale.ROOT);
            if (norm.equals(want) || norm.matches(java.util.regex.Pattern.quote(want) + "\\[\\d+]")) {
                Arrays.stream(v.split(",")).map(String::strip).filter(x -> !x.isEmpty()).forEach(out::add);
            }
        });
        return out;
    }

    /** Where the Spring configuration sets {@code key}: the config file and line, else the service's YAML at line 1. */
    private String[] configLine(String key) {
        java.nio.file.Path dir = dev.logast.SpringProperties.appResources(ctx.a.project.root());
        String leaf = key.substring(key.lastIndexOf('.') + 1);
        if (dir != null) {
            for (String name : dev.logast.SpringProperties.FILES.reversed()) {   // the file that wins first
                java.nio.file.Path f = dir.resolve(name);
                if (!java.nio.file.Files.exists(f)) {
                    continue;
                }
                String[] lines = SetupRules.read(f).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String l = lines[i].strip();
                    if (name.endsWith(".properties") ? l.startsWith(key + "=") || l.startsWith(key + " ") || l.startsWith(key + ":")
                            : l.startsWith(leaf + ":") || l.startsWith(key + ":")) {
                        return new String[]{ctx.a.project.root().relativize(f).toString().replace('\\', '/'), String.valueOf(i + 1)};
                    }
                }
            }
        }
        return new String[]{ServiceYaml.path(ctx), "1"};
    }

    /** A Spring Boot duration in milliseconds: {@code secondsByDefault} for properties declared with @DurationUnit(SECONDS). */
    static Long durationMillis(String value, boolean secondsByDefault) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        return v.matches("\\d+") && !secondsByDefault ? Long.valueOf(v) : millis(v);
    }

    /**
     * SHUTDOWN_NOT_GRACEFUL (#29, #90 item 2). "Graceful shutdown is enabled by default with all three embedded web
     * servers (Jetty, Reactor Netty, and Tomcat) and with both reactive and servlet-based web applications"; it is
     * disabled with {@code server.shutdown: immediate}, and the grace period is
     * {@code spring.lifecycle.timeout-per-shutdown-phase} (Spring Boot reference, "Graceful Shutdown",
     * docs.spring.io/spring-boot/reference/web/graceful-shutdown.html; default 30s, application properties appendix,
     * 3.5 and 4.x). Reported: a web service that turns it off, and a shutdown phase shorter than a timeout the
     * configuration gives running work (HTTP read timeout, MVC async request timeout, transaction timeout), which then
     * is cut off at shutdown. The query timeout logast writes (resilience.dbQueryTimeout) is a stop for "forever", not
     * how long work takes, so it is not compared. Service-level: only with a @SpringBootApplication.
     */
    private void shutdown() {
        if (bootApplication() == null) {
            return;
        }
        boolean web = ctx.a.entrypoints.stream().anyMatch(ep -> ep.kind() == Entrypoint.Kind.HTTP);
        if (web && "immediate".equalsIgnoreCase(value("server.shutdown"))) {
            String[] at = configLine("server.shutdown");
            ctx.order(Rules.SHUTDOWN_NOT_GRACEFUL, "shutdown-immediate").at(at[0], Integer.parseInt(at[1]))
                    .title("server.shutdown: immediate turns graceful shutdown off: every rollout, restart or scale-down cuts "
                            + "off the requests still running (Spring Boot shuts down gracefully by default)")
                    .suggestion("Remove server.shutdown: immediate (graceful is the default for Tomcat, Jetty and Reactor "
                            + "Netty): the web server then stops taking new requests and lets running ones finish within "
                            + "spring.lifecycle.timeout-per-shutdown-phase (30s by default).")
                    .basis("the service has HTTP endpoints and its Spring configuration sets server.shutdown: immediate")
                    .doneWhen("server.shutdown is not immediate in the Spring configuration");
        }
        String phaseValue = value("spring.lifecycle.timeout-per-shutdown-phase");
        Long phase = phaseValue == null ? Long.valueOf(30_000) : durationMillis(phaseValue, false);
        if (phase == null) {
            return;
        }
        String longest = null;
        long longestMs = 0;
        List<String[]> bounds = new ArrayList<>();   // key, value, seconds by default
        ctx.a.springProperties.forEach((k, v) -> {
            if (k.matches("spring\\.http\\.clients?\\.(?:[a-z-]+\\.)*read-timeout") || k.equals("spring.mvc.async.request-timeout")) {
                bounds.add(new String[]{k, v, "ms"});
            }
        });
        if (value("spring.transaction.default-timeout") != null) {
            bounds.add(new String[]{"spring.transaction.default-timeout", value("spring.transaction.default-timeout"), "s"});
        }
        for (String[] bound : bounds) {
            Long ms = durationMillis(bound[1], bound[2].equals("s"));
            if (ms != null && ms > longestMs) {
                longestMs = ms;
                longest = bound[0] + " = " + bound[1];
            }
        }
        if (longest == null || longestMs <= phase) {
            return;
        }
        String[] at = phaseValue == null ? new String[]{ServiceYaml.path(ctx), "1"} : configLine("spring.lifecycle.timeout-per-shutdown-phase");
        ctx.order(Rules.SHUTDOWN_NOT_GRACEFUL, "shutdown-phase").at(at[0], Integer.parseInt(at[1]))
                .title("Shutdown waits " + (phaseValue == null ? "30s (spring.lifecycle.timeout-per-shutdown-phase, the default)"
                        : phaseValue + " (spring.lifecycle.timeout-per-shutdown-phase)") + ", less than the service lets work "
                        + "run (" + longest + "): work still running when a rollout stops the instance is cut off")
                .suggestion("Raise spring.lifecycle.timeout-per-shutdown-phase above the longest request or transaction, and "
                        + "the platform's grace period with it (Kubernetes kills the pod after terminationGracePeriodSeconds, "
                        + "30s by default); or lower that timeout.")
                .basis("timeout-per-shutdown-phase " + (phaseValue == null ? "is not set (30s)" : "is " + phaseValue)
                        + "; the longest timeout the Spring configuration sets for requests and transactions is " + longest)
                .doneWhen("spring.lifecycle.timeout-per-shutdown-phase (30s when not set) is at least as long as every "
                        + "HTTP client read timeout, spring.mvc.async.request-timeout and spring.transaction.default-timeout "
                        + "in the Spring configuration");
    }

    /** Health indicators of systems outside the instance (Spring Boot's auto-configured indicator names). */
    private static final Set<String> EXTERNAL_INDICATORS = Set.of("db", "r2dbc", "redis", "mongo", "rabbit", "jms",
            "elasticsearch", "cassandra", "couchbase", "neo4j", "ldap", "mail", "hazelcast", "influxdb", "solr", "*");

    /**
     * HEALTH_PROBE_MISCONFIGURED and READINESS_WITHOUT_DATABASE (#30, #90 item 2), from Spring Boot reference,
     * "Kubernetes Probes" (docs.spring.io/spring-boot/reference/actuator/endpoints.html):
     * <ul>
     *   <li>the liveness/readiness groups are on by default in Boot 4 (management.endpoint.health.probes.enabled
     *       defaults to true) and on Kubernetes in Boot 3 ("automatically enabled only if the application runs in a
     *       Kubernetes environment"); an explicit false switches them off on Kubernetes too
     *       (AvailabilityProbesAutoConfiguration.ProbesCondition, 3.5): reported;</li>
     *   <li>"The liveness probe should not depend on health checks for external systems": a database, broker or cache
     *       in the liveness group restarts every instance when it is down: reported;</li>
     *   <li>"Spring Boot does not add other health indicators to these groups"; whether readiness includes a shared
     *       database is "a judgement call": a low-severity report asking for the decision, never written by logast.</li>
     * </ul>
     * Service-level: only with a @SpringBootApplication, and the readiness note only with Actuator (on the classpath or
     * configured under management.*).
     */
    private void probes() {
        if (bootApplication() == null) {
            return;
        }
        String enabledKey = value("management.endpoint.health.probes.enabled") != null ? "management.endpoint.health.probes.enabled"
                : value("management.health.probes.enabled") != null ? "management.health.probes.enabled" : null;
        boolean disabled = enabledKey != null && "false".equalsIgnoreCase(value(enabledKey));
        if (ctx.enabled(Rules.HEALTH_PROBE_MISCONFIGURED) && disabled) {
            String[] at = configLine(enabledKey);
            ctx.order(Rules.HEALTH_PROBE_MISCONFIGURED, "probes-disabled").at(at[0], Integer.parseInt(at[1]))
                    .title(enabledKey + ": false switches the liveness and readiness groups off, on Kubernetes too: "
                            + "/actuator/health/liveness and /readiness disappear, and probes pointed at /actuator/health "
                            + "restart or unroute the instance whenever any dependency is down")
                    .suggestion("Remove the property (the probe groups are on by default in Spring Boot 4 and on Kubernetes in "
                            + "Spring Boot 3), and point the platform's liveness and readiness probes at "
                            + "/actuator/health/liveness and /actuator/health/readiness.")
                    .basis("the Spring configuration sets " + enabledKey + ": false")
                    .doneWhen(enabledKey + " is not false in the Spring configuration");
        }
        List<String> liveness = values("management.endpoint.health.group.liveness.include");
        List<String> external = liveness.stream().filter(i -> EXTERNAL_INDICATORS.contains(i.toLowerCase(Locale.ROOT))).toList();
        if (ctx.enabled(Rules.HEALTH_PROBE_MISCONFIGURED) && !external.isEmpty()) {
            String[] at = configLine("management.endpoint.health.group.liveness.include");
            ctx.order(Rules.HEALTH_PROBE_MISCONFIGURED, "liveness-external").at(at[0], Integer.parseInt(at[1]))
                    .title("The liveness group includes " + String.join(", ", external) + ": when that system is down, every "
                            + "instance fails liveness and is restarted, which does not bring it back (liveness should not "
                            + "depend on external systems)")
                    .suggestion("Keep liveness to the instance itself (management.endpoint.health.group.liveness.include: "
                            + "livenessState); if the dependency should take the instance out of traffic, put it in the "
                            + "readiness group instead.")
                    .basis("management.endpoint.health.group.liveness.include lists " + String.join(", ", external))
                    .doneWhen("management.endpoint.health.group.liveness.include names none of "
                            + String.join(", ", new TreeSet<>(EXTERNAL_INDICATORS)));
        }
        List<String> readiness = values("management.endpoint.health.group.readiness.include");
        if (ctx.enabled(Rules.READINESS_WITHOUT_DATABASE) && !disabled && actuator() && usesDatabase()
                && readiness.stream().noneMatch(i -> i.equalsIgnoreCase("db") || i.equals("*"))) {
            ctx.order(Rules.READINESS_WITHOUT_DATABASE, "readiness-db").at(ServiceYaml.path(ctx), 1)
                    .title("The readiness group does not include the database (db): an instance that lost its database keeps "
                            + "receiving traffic and fails it (Spring Boot adds no other checks to readiness by default)")
                    .suggestion("Decide, as the Spring Boot reference puts it a judgement call: include it "
                            + "(management.endpoint.health.group.readiness.include: readinessState,db), and with a database "
                            + "every instance shares, all go out of service together while it is down; or leave it out and "
                            + "handle the failures in the callers, then switch this rule off for the service "
                            + "(rules: {READINESS_WITHOUT_DATABASE: {enabled: false}} in logast.yaml).")
                    .basis("the service uses a database (JDBC / JPA) and Actuator, and "
                            + (readiness.isEmpty() ? "management.endpoint.health.group.readiness.include is not set"
                            : "management.endpoint.health.group.readiness.include is " + String.join(",", readiness)))
                    .doneWhen("management.endpoint.health.group.readiness.include names db, or the rule is switched off in "
                            + "logast.yaml");
        }
    }

    /** Spring Boot Actuator: on the classpath (Boot 4 or Boot 3 location of the health endpoint) or configured. */
    private boolean actuator() {
        var probe = ctx.a.project.probe();
        return probe.hasClass("org.springframework.boot.health.actuate.endpoint.HealthEndpoint")
                || probe.hasClass("org.springframework.boot.actuate.health.HealthEndpoint")
                || ctx.a.springProperties.keySet().stream().anyMatch(k -> k.startsWith("management.endpoint.")
                || k.startsWith("management.endpoints.") || k.startsWith("management.health."));
    }

    /**
     * A relational database: a JdbcTemplate / JdbcClient / DataSource field, JPA, or @Transactional methods (Spring
     * Boot's auto-configured transaction manager is the DataSource's or JPA's).
     */
    private boolean usesDatabase() {
        if (ctx.methods().stream().anyMatch(m -> TRANSACTIONAL.stream().anyMatch(m::hasAnnotation))) {
            return true;
        }
        return ctx.methodsOwners().stream().anyMatch(c -> usesJpa(c) || c.decl().getBody().getStatements().stream()
                .anyMatch(s -> s instanceof J.VariableDeclarations vd && (JDBC_TYPES.stream().anyMatch(t -> TypeUtils.isAssignableTo(t, vd.getType()))
                        || TypeUtils.isOfClassType(vd.getType(), "javax.sql.DataSource"))));
    }

    // ---- caches (#90 item 7) ---------------------------------------------------------------------

    /** Cache libraries in Spring Boot's detection order (Spring Boot reference, "Caching", Supported Cache Providers). */
    private static final List<String[]> CACHE_PROVIDERS = List.of(
            new String[]{"jcache", "javax.cache.Caching", "javax.cache"},
            new String[]{"hazelcast", "com.hazelcast.core.HazelcastInstance", "hazelcast"},
            new String[]{"infinispan", "org.infinispan.spring.embedded.provider.SpringEmbeddedCacheManager", "infinispan"},
            new String[]{"couchbase", "com.couchbase.client.java.Cluster", "couchbase"},
            new String[]{"redis", "org.springframework.data.redis.connection.RedisConnectionFactory", "redis"},
            new String[]{"caffeine", "com.github.benmanes.caffeine.cache.Caffeine", "caffeine"},
            new String[]{"cache2k", "org.cache2k.Cache2kBuilder", "cache2k"});

    /**
     * CACHE_UNBOUNDED (#90 item 7). With @EnableCaching and no CacheManager bean, Spring Boot picks the first cache
     * library it finds; with none, "a simple implementation using a ConcurrentHashMap as the cache store is configured.
     * This is the default if no caching library is present", "not really recommended for production usage" (Spring
     * Boot reference, "Caching", docs.spring.io/spring-boot/reference/io/caching.html, 3.5 and 4.x); that cache "does
     * not provide any management, persistence capabilities, or eviction contracts" (Spring Framework reference, "Cache
     * Store Configuration"). Caffeine is bounded only by maximumSize / maximumWeight in its spec or bean. Reported: every
     * distinct key stays in memory. Library detection: logast's classpath, or the library named in the build file.
     */
    private void caches() {
        ClassInfo enabling = ctx.methodsOwners().stream()
                .filter(c -> c.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, "org.springframework.cache.annotation.EnableCaching")))
                .min(Comparator.comparing(c -> c.file().relPath())).orElse(null);
        if (enabling == null) {
            return;
        }
        int line = Pos.line(enabling.decl().getName());
        String why = unboundedCache(enabling);
        if (why == null || ctx.ignored(enabling.file(), line, Rules.CACHE_UNBOUNDED)) {
            return;
        }
        ctx.order(Rules.CACHE_UNBOUNDED, "cache-unbounded").at(enabling.file().relPath(), line)
                .title("Caches without a size bound (" + why + "): every distinct key stays in memory until the heap runs out")
                .suggestion("Bound the caches: Caffeine (com.github.ben-manes.caffeine:caffeine) with spring.cache.caffeine.spec: "
                        + "maximumSize=..,expireAfterWrite=.., or a CacheManager bean whose caches have a maximum size; "
                        + "Spring Boot's simple ConcurrentHashMap provider never evicts.")
                .basis("@EnableCaching on " + enabling.simpleName() + "; " + why)
                .doneWhen("the caches have a size bound: a cache library other than the ConcurrentHashMap one is used "
                        + "(classpath, build file, spring.cache.type or a CacheManager bean), and a Caffeine spec or bean sets "
                        + "maximumSize or maximumWeight");
    }

    /** Why the service's caches are unbounded, or null when they are bounded (or logast cannot tell). */
    private String unboundedCache(ClassInfo enabling) {
        List<MethodInfo> managers = ctx.methods().stream().filter(m -> m.decl().getBody() != null
                && (beanReturning(m, "CacheManager") || m.decl().getSimpleName().equals("cacheManager")
                && m.owner().type() != null && TypeUtils.isAssignableTo("org.springframework.cache.annotation.CachingConfigurer", m.owner().type())))
                .toList();
        if (!managers.isEmpty()) {
            String text = String.join("\n", managers.stream().map(m -> m.file().slice(Pos.start(m.decl()), Pos.end(m.decl()))).toList());
            if (text.contains("ConcurrentMapCacheManager") || text.contains("ConcurrentMapCacheFactoryBean")
                    || text.contains("ConcurrentMapCache(")) {
                return "the CacheManager bean keeps them in ConcurrentHashMaps (no eviction)";
            }
            if (text.contains("CaffeineCacheManager") && !bounded(text) && !caffeineBounded()) {
                return "the CaffeineCacheManager bean sets no maximumSize";
            }
            return null;
        }
        String type = value("spring.cache.type");
        if (type != null) {
            return switch (type.strip().toLowerCase(Locale.ROOT)) {
                case "simple" -> "spring.cache.type: simple keeps them in ConcurrentHashMaps (no eviction)";
                case "caffeine" -> caffeineBounded() ? null : "the Caffeine spec sets no maximumSize";
                default -> null;
            };
        }
        if (ctx.methods().stream().anyMatch(m -> beanReturning(m, "Cache") && !beanReturning(m, "CacheManager"))) {
            return null;   // Generic: Cache beans of the service's own
        }
        String build = buildText(enabling);
        for (String[] provider : CACHE_PROVIDERS) {
            if (ctx.a.project.probe().hasClass(provider[1]) || build.contains(provider[2])) {
                return provider[0].equals("caffeine") && !caffeineBounded() ? "the Caffeine spec sets no maximumSize" : null;
            }
        }
        return "no cache library is on the classpath, so Spring Boot falls back to its simple provider (a ConcurrentHashMap "
                + "per cache, no eviction)";
    }

    /** A @Bean method whose declared return type is named {@code simpleName} or ends with it (CaffeineCacheManager). */
    private static boolean beanReturning(MethodInfo m, String simpleName) {
        if (m.decl().getReturnTypeExpression() == null || m.decl().getLeadingAnnotations().stream()
                .noneMatch(a -> Annotations.is(a, "org.springframework.context.annotation.Bean"))) {
            return false;
        }
        String written = m.decl().getReturnTypeExpression().printTrimmed().replaceAll("<.*", "");
        return written.equals(simpleName) || written.endsWith(simpleName) && simpleName.equals("CacheManager");
    }

    private static boolean bounded(String text) {
        return text.contains("maximumSize") || text.contains("maximumWeight");
    }

    /** spring.cache.caffeine.spec, or a Caffeine / CaffeineSpec bean, sets a maximum size. */
    private boolean caffeineBounded() {
        String spec = value("spring.cache.caffeine.spec");
        if (spec != null && bounded(spec)) {
            return true;
        }
        return ctx.methods().stream().filter(m -> beanReturning(m, "Caffeine") || beanReturning(m, "CaffeineSpec"))
                .anyMatch(m -> bounded(m.file().slice(Pos.start(m.decl()), Pos.end(m.decl()))));
    }

    /** The Gradle or Maven build of the class's module (with the scripts it applies), or "". */
    private String buildText(ClassInfo c) {
        java.nio.file.Path root = ctx.a.project.root();
        for (java.nio.file.Path dir = c.file().absPath().getParent(); dir != null && dir.startsWith(root); dir = dir.getParent()) {
            for (String n : List.of("build.gradle.kts", "build.gradle", "pom.xml")) {
                java.nio.file.Path f = dir.resolve(n);
                if (java.nio.file.Files.exists(f)) {
                    return n.equals("pom.xml") ? SetupRules.read(f) : BuildScripts.of(root, f).text();
                }
            }
        }
        return "";
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

    // ---- RabbitMQ -------------------------------------------------------------------------------

    private static final String RABBIT_LISTENER = "org.springframework.amqp.rabbit.annotation.RabbitListener";

    /**
     * RABBIT_NO_ERROR_HANDLER (#90 item 5): "By default, if retries are disabled and the listener throws an exception,
     * the delivery is retried indefinitely" (Spring Boot reference, "AMQP", Receiving a Message; same in 3.5 and 4.x):
     * Spring AMQP rejects the message and the broker requeues it (defaultRequeueRejected is true by default; Spring AMQP
     * reference, "Exception Handling"), so a message that always fails comes back without end. Not reported when
     * listener retries are on (spring.rabbitmq.listener.&lt;type&gt;.retry.enabled: exhausted retries reject without
     * requeue), default-requeue-rejected is false, a source configures the container (setDefaultRequeueRejected, an
     * advice chain, RetryInterceptorBuilder), or the listener's own class throws AmqpRejectAndDontRequeueException.
     * Listeners with an explicit ackMode settle messages themselves; stream listeners do not requeue. Report: where a
     * failed message goes (dropped, a dead-letter exchange) is a decision.
     */
    private void rabbit() {
        String type = propertyValue("spring.rabbitmq.listener.type").orElse("simple").toLowerCase(Locale.ROOT);
        if (type.equals("stream")
                || propertyValue("spring.rabbitmq.listener." + type + ".retry.enabled").filter("true"::equalsIgnoreCase).isPresent()
                || propertyValue("spring.rabbitmq.listener." + type + ".default-requeue-rejected").filter("false"::equalsIgnoreCase).isPresent()
                || mentioned("setDefaultRequeueRejected") || mentioned("RetryInterceptorBuilder") || mentioned("setAdviceChain")) {
            return;
        }
        List<MethodInfo> listeners = ctx.a.entrypoints.stream().filter(ep -> ep.kind() == Entrypoint.Kind.RABBIT)
                .map(Entrypoint::method)
                .filter(m -> m.decl().getLeadingAnnotations().stream().filter(a -> Annotations.is(a, RABBIT_LISTENER))
                        .noneMatch(a -> Annotations.attribute(a, "ackMode").isPresent()))
                .filter(m -> !m.file().text().contains("AmqpRejectAndDontRequeueException"))
                .filter(m -> !ctx.ignored(m.file(), m.line(), Rules.RABBIT_NO_ERROR_HANDLER))
                .sorted(Comparator.comparing((MethodInfo m) -> m.file().relPath()).thenComparingInt(MethodInfo::line)).toList();
        if (listeners.isEmpty()) {
            return;
        }
        MethodInfo first = listeners.getFirst();
        String prefix = "spring.rabbitmq.listener." + type;
        OrderBuilder b = ctx.order(Rules.RABBIT_NO_ERROR_HANDLER, "rabbit-errors").at(first.file().relPath(), first.line())
                .title("RabbitMQ listeners on the default error handling: a message whose listener throws is rejected and "
                        + "requeued, so a message that always fails is redelivered without end ("
                        + String.join(", ", listeners.stream().map(MethodInfo::label).toList()) + ")")
                .suggestion("Turn on listener retries (" + prefix + ".retry.enabled: true): once they are exhausted the "
                        + "message is rejected without requeue, and dropped unless the queue has a dead-letter exchange "
                        + "(x-dead-letter-exchange), so declare one to keep it. Or set " + prefix
                        + ".default-requeue-rejected: false, or throw AmqpRejectAndDontRequeueException for failures a "
                        + "redelivery cannot fix.")
                .basis("Spring Boot: \"if retries are disabled and the listener throws an exception, the delivery is retried "
                        + "indefinitely\"; the Spring configuration sets neither " + prefix + ".retry.enabled: true nor "
                        + prefix + ".default-requeue-rejected: false, no source configures the container's requeue or "
                        + "advice chain, and the listeners neither set an ackMode nor throw AmqpRejectAndDontRequeueException")
                .doneWhen(prefix + ".retry.enabled: true or " + prefix + ".default-requeue-rejected: false is set, a "
                        + "source calls setDefaultRequeueRejected / setAdviceChain or uses RetryInterceptorBuilder, or the "
                        + "listener's class throws AmqpRejectAndDontRequeueException");
        listeners.stream().skip(1).forEach(m -> b.location(m.file().relPath(), m.line(), Rules.RABBIT_NO_ERROR_HANDLER));
    }

    /** The value the Spring configuration sets for {@code key} (relaxed binding: kebab-case, camelCase). */
    private Optional<String> propertyValue(String key) {
        String want = key.replace("-", "").toLowerCase(Locale.ROOT);
        return ctx.a.springProperties.entrySet().stream()
                .filter(e -> e.getKey().replace("-", "").toLowerCase(Locale.ROOT).equals(want))
                .map(e -> e.getValue().trim()).findFirst();
    }

    // ---- retries inside an outer transaction ----------------------------------------------------

    /**
     * RETRY_IN_TRANSACTION (#90 item 4 follow-up): retry is always applied outside {@code @Transactional}, so "Each
     * retry attempt starts a fresh transaction" (Framework reference, "Resilience Features"). That holds only when the
     * method starts the transaction: called from inside another transaction, with the default propagation it joins that
     * one, and "a rollback-only marker set in the inner transaction scope does affect the outer transaction's chance to
     * actually commit" (Framework reference, "Transaction Propagation", PROPAGATION_REQUIRED). Every attempt then runs
     * in the one transaction the first failure already marked rollback-only, and the caller's commit ends in an
     * UnexpectedRollbackException. Reported for a {@code @Retryable} (Framework 7 or Spring Retry) method whose own
     * {@code @Transactional} joins (not REQUIRES_NEW / NESTED / NOT_SUPPORTED / NEVER), reached through a bean call
     * (another class: the proxy applies) from a transactional method, up to 4 calls out.
     */
    private void retryInTransaction() {
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null || !retryable(m) || !joinsTransaction(m)
                    || ctx.ignored(m.file(), m.line(), Rules.RETRY_IN_TRANSACTION)) {
                continue;
            }
            List<String> path = outerTransaction(m);
            if (path == null) {
                continue;
            }
            ctx.order(Rules.RETRY_IN_TRANSACTION, m, m.line(), "retry-in-tx")
                    .title(m.label() + " is @Retryable and @Transactional, but runs inside the transaction of "
                            + String.join(" → ", path) + ": its attempts join that transaction, so after the first failure "
                            + "they retry in a transaction already marked rollback-only, and the caller's commit fails with "
                            + "UnexpectedRollbackException")
                    .suggestion("Retry the whole transaction: put @Retryable on the method that starts it (retry wraps "
                            + "@Transactional, so each attempt gets a fresh transaction), or give " + m.label()
                            + " @Transactional(propagation = Propagation.REQUIRES_NEW) so each attempt commits or rolls back "
                            + "on its own (it takes a second connection while the caller's is held).")
                    .doneWhen("the method is no longer reached from another transactional method, or its @Transactional "
                            + "uses REQUIRES_NEW, NESTED or NOT_SUPPORTED");
        }
    }

    private boolean retryable(MethodInfo m) {
        return Stream.concat(m.decl().getLeadingAnnotations().stream(), m.owner().decl().getLeadingAnnotations().stream())
                .map(a -> annotationFqn(a, m.file()))
                .anyMatch(f -> (RESILIENCE_PKG + "Retryable").equals(f) || (SPRING_RETRY_PKG + "Retryable").equals(f));
    }

    /** The method's transaction annotation (its own, else its class's), or null. */
    private static J.Annotation transactional(MethodInfo m) {
        return Stream.concat(m.decl().getLeadingAnnotations().stream(), m.owner().decl().getLeadingAnnotations().stream())
                .filter(a -> TRANSACTIONAL.stream().anyMatch(t -> Annotations.is(a, t))).findFirst().orElse(null);
    }

    private static String propagation(J.Annotation a) {
        return (Annotations.is(a, TRANSACTIONAL.getFirst()) ? Annotations.attribute(a, "propagation")
                : Annotations.attribute(a, "value")).map(e -> e.printTrimmed()).orElse("REQUIRED");
    }

    /** @Transactional whose propagation joins a caller's transaction. */
    private static boolean joinsTransaction(MethodInfo m) {
        J.Annotation a = transactional(m);
        String p = a == null ? "" : propagation(a);
        return a != null && Stream.of("REQUIRES_NEW", "NESTED", "NOT_SUPPORTED", "NEVER").noneMatch(p::endsWith);
    }

    /** @Transactional that holds a transaction for what it calls (not SUPPORTS / NOT_SUPPORTED / NEVER). */
    private static boolean holdsTransaction(MethodInfo m) {
        J.Annotation a = transactional(m);
        String p = a == null ? "" : propagation(a);
        return a != null && Stream.of("SUPPORTS", "NOT_SUPPORTED", "NEVER").noneMatch(p::endsWith);
    }

    /**
     * The nearest transactional caller of {@code m} on the same thread, up to 4 calls out, as a path of labels from it
     * to {@code m}; null when there is none. The call into {@code m} must come from another class (a self-call skips
     * the proxy, so neither retry nor transaction apply to it).
     */
    private List<String> outerTransaction(MethodInfo m) {
        Deque<List<MethodInfo>> todo = new ArrayDeque<>(List.of(List.of(m)));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            List<MethodInfo> path = todo.poll();
            MethodInfo callee = path.getFirst();
            if (!seen.add(callee.key()) || path.size() > 5) {
                continue;
            }
            for (var e : ctx.a.graph.in(callee)) {
                MethodInfo caller = e.from();
                if (e.async() != dev.logast.graph.CallGraph.Async.NONE
                        || callee.key().equals(m.key()) && caller.owner().fqn().equals(m.owner().fqn())) {
                    continue;
                }
                List<MethodInfo> next = new ArrayList<>(path);
                next.addFirst(caller);
                if (holdsTransaction(caller)) {
                    return next.stream().map(MethodInfo::label).toList();
                }
                todo.add(next);
            }
        }
        return null;
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
