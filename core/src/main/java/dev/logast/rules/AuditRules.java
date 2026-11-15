package dev.logast.rules;

import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import dev.logast.policy.Policy;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * {@code audit} pack: actions that must leave an audit record, not just a log line.
 * <ul>
 *   <li>AUDIT_MISSING: an entrypoint matching {@code audit.actions} (deletes, admin endpoints, exports, grants,
 *       refunds, ...) from which no audit emitter is reachable;</li>
 *   <li>AUDIT_SECURITY_EVENTS: Spring Security is used but nothing records authentication failures, successes or
 *       denied access.</li>
 * </ul>
 * An emitter is a call listed in {@code audit.emitters} ({@code com.x.AuditService} or {@code com.x.ActivityLogger#log});
 * with none listed, any call on a type named *Audit* or passing an *Audit* argument (an AuditAction enum value).
 */
final class AuditRules {
    private static final Set<String> SECURITY_EVENTS = Set.of(
            "org.springframework.security.authentication.event.AbstractAuthenticationEvent",
            "org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent",
            "org.springframework.security.authentication.event.AuthenticationSuccessEvent",
            "org.springframework.security.authorization.event.AuthorizationDeniedEvent",
            "org.springframework.security.authorization.event.AuthorizationEvent");

    private final RuleContext ctx;
    private final Policy.Audit cfg;

    AuditRules(RuleContext ctx) {
        this.ctx = ctx;
        this.cfg = ctx.policy.audit();
    }

    void run() {
        if (ctx.enabled(Rules.AUDIT_MISSING) || ctx.enabled(Rules.AUDIT_FAILURES_UNRECORDED)) {
            missing();
        }
        if (ctx.enabled(Rules.AUDIT_SECURITY_EVENTS)) {
            securityEvents();
        }
        if (ctx.enabled(Rules.AUDIT_RECORD_INCOMPLETE)) {
            incompleteRecords();
        }
        if (ctx.enabled(Rules.AUDIT_LOG_MIXED)) {
            auditLoggers();
        }
    }

    // ---- auditable actions ----------------------------------------------------------------------

    private void missing() {
        if (cfg.actions().isEmpty()) {
            return;
        }
        Set<String> emitting = new HashSet<>();
        for (MethodInfo m : ctx.a.index.methods()) {
            if (m.decl().getBody() != null && emits(m)) {
                emitting.add(m.key());
            }
        }
        Set<String> terms = new HashSet<>();
        ctx.policy.terms().forEach(t -> terms.add(t.toLowerCase(Locale.ROOT)));
        for (Entrypoint ep : ctx.a.entrypoints) {
            if (!write(ep)) {
                continue;
            }
            Policy.AuditAction action = cfg.actions().stream().filter(a -> matches(a, ep, terms)).findFirst().orElse(null);
            if (action == null) {
                continue;
            }
            if (ctx.a.paths.reachableFrom(ep).stream().anyMatch(emitting::contains)) {
                if (ctx.enabled(Rules.AUDIT_FAILURES_UNRECORDED) && !failuresAudited(ep, emitting)) {
                    failuresUnrecorded(ep, action);
                }
                continue;
            }
            MethodInfo m = ep.method();
            if (!ctx.enabled(Rules.AUDIT_MISSING) || ctx.ignored(m.file(), m.line(), Rules.AUDIT_MISSING)) {
                continue;
            }
            ctx.order(Rules.AUDIT_MISSING, m, m.line(), "audit")
                    .title(ep.label() + " is an auditable action (" + describe(action) + ") but records no audit event: "
                            + "who did what to which record is only in the logs, if at all")
                    .suggestion("Record an audit event on the success path with actor, tenant and the affected id"
                            + (cfg.emitters().isEmpty() ? "" : " (" + String.join(", ", cfg.emitters()) + ")")
                            + "; or mark it // logast:ignore AUDIT_MISSING if it is not auditable.")
                    .context(ctx.context(m, dev.logast.parse.Pos.start(m.decl()), Text.oneLine(ep.label(), 160), null));
        }
    }

    /**
     * AUDIT_FAILURES_UNRECORDED (report): the action records an audit event, but only on its success path. A rejected
     * or failed attempt (denied, invalid, an error halfway) leaves no record, and those are what an audit is for.
     */
    private void failuresUnrecorded(Entrypoint ep, Policy.AuditAction action) {
        MethodInfo m = ep.method();
        if (ctx.ignored(m.file(), m.line(), Rules.AUDIT_FAILURES_UNRECORDED)) {
            return;
        }
        ctx.order(Rules.AUDIT_FAILURES_UNRECORDED, m, m.line(), "audit-failures")
                .title(ep.label() + " records an audit event only when it succeeds: a rejected or failed attempt leaves no "
                        + "record (" + describe(action) + ")")
                .suggestion("Record the attempt's outcome either way: an audit call in a catch (with the failure) or in a "
                        + "finally (with the outcome), or record the attempt before the work starts.");
    }

    /**
     * Failed attempts are recorded, or there is no failure to miss: some audit call reachable from the action sits in a
     * catch or finally block, or no audit call comes after other work (the audit call is the action, or comes first).
     */
    private boolean failuresAudited(Entrypoint ep, Set<String> emitting) {
        boolean afterWork = false;
        for (String key : ctx.a.paths.reachableFrom(ep)) {
            MethodInfo m = ctx.a.index.method(key);
            if (m == null || !emitting.contains(key) || m.decl().getBody() == null) {
                continue;
            }
            boolean[] found = {false};
            boolean[] work = {false};
            boolean[] auditAfterWork = {false};
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    J.MethodInvocation r = super.visitMethodInvocation(mi, p);   // arguments first: they run before the call
                    if (emitter(mi)) {
                        if (inCatchOrFinally()) {
                            found[0] = true;
                        } else if (work[0]) {
                            auditAfterWork[0] = true;
                        }
                    } else if (!logger(mi) && !support(mi)) {
                        work[0] = true;
                    }
                    return r;
                }

                private boolean inCatchOrFinally() {
                    for (org.openrewrite.Cursor c = getCursor(); c != null; c = c.getParent()) {
                        if (c.getValue() instanceof J.Try.Catch) {
                            return true;
                        }
                        if (c.getValue() instanceof J.Block b && c.getParent() != null
                                && c.getParent().getValue() instanceof J.Try t && t.getFinally() == b) {
                            return true;
                        }
                    }
                    return false;
                }
            }.visit(m.decl().getBody(), 0, new org.openrewrite.Cursor(new org.openrewrite.Cursor(null, org.openrewrite.Cursor.ROOT_VALUE), m.decl()));
            if (found[0]) {
                return true;
            }
            afterWork |= auditAfterWork[0];
        }
        return !afterWork;
    }

    /** logast's own support code (MdcScope, ObservedContext, Outcomes) and metrics catalogs: not the action's work. */
    private static final Set<String> SUPPORT_NAMES = Set.of("MdcScope", "ObservedContext", "Outcomes", "ContextExecutors", "AfterCommit");

    private boolean support(J.MethodInvocation mi) {
        if (mi.getMethodType() == null) {
            // the starter not on logast's classpath (dependency mode): its calls are recognised by name
            for (org.openrewrite.java.tree.Expression e = mi; e instanceof J.MethodInvocation call; e = call.getSelect()) {
                if (call.getSelect() instanceof J.Identifier id && SUPPORT_NAMES.contains(id.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }
        String owner = mi.getMethodType().getDeclaringType().getFullyQualifiedName();
        dev.logast.model.ClassInfo c = ctx.a.index.clazz(owner);
        return owner.startsWith(ctx.a.supportPackage + ".") || owner.startsWith(ctx.policy.support().packageName() + ".")
                || c != null && c.file().text().contains(CatalogRules.MARKER);
    }

    private static boolean logger(J.MethodInvocation mi) {
        return mi.getMethodType() != null && TypeUtils.isAssignableTo("org.slf4j.Logger", mi.getMethodType().getDeclaringType());
    }

    // ---- audit lines apart from the application log --------------------------------------------

    /**
     * AUDIT_LOG_MIXED (AUTO): an audit class (named *Audit*, or an emitter in audit.emitters) logs through a logger
     * named after the class, so its lines mix with the application log. Renamed to the policy's audit logger
     * ({@code LoggerFactory.getLogger("audit")}, Lombok {@code @Slf4j(topic = "audit")}), the log pipeline can route them
     * apart: their own index, their own retention.
     */
    private void auditLoggers() {
        String name = cfg.logger();
        for (ClassInfo c : ctx.methodsOwners()) {
            if (!auditClass(c)) {
                continue;
            }
            dev.logast.parse.SourceFileInfo f = c.file();
            for (Statement s : c.decl().getBody().getStatements()) {
                if (s instanceof J.VariableDeclarations vd && TypeUtils.isAssignableTo("org.slf4j.Logger", vd.getType())) {
                    for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                        if (v.getInitializer() instanceof J.MethodInvocation init && init.getSimpleName().equals("getLogger")
                                && !init.getArguments().isEmpty() && !(init.getArguments().getFirst() instanceof J.Literal)) {
                            Expression arg = init.getArguments().getFirst();
                            order(c, Pos.line(vd)).edit(dev.logast.orders.Edit.replace(f.relPath(), Pos.start(arg), Pos.end(arg),
                                    Text.javaString(name)));
                        }
                    }
                }
            }
            for (J.Annotation a : c.decl().getLeadingAnnotations()) {
                if (a.getSimpleName().equals("Slf4j") && (a.getArguments() == null || a.getArguments().isEmpty()
                        || a.getArguments().getFirst() instanceof J.Empty)) {
                    order(c, Pos.line(a)).edit(dev.logast.orders.Edit.replace(f.relPath(), Pos.start(a), Pos.end(a),
                            "@Slf4j(topic = " + Text.javaString(name) + ")"));
                }
            }
        }
    }

    private OrderBuilder order(ClassInfo c, int line) {
        return ctx.order(Rules.AUDIT_LOG_MIXED, "audit-logger|" + c.fqn() + "|" + line).at(c.file().relPath(), line)
                .title(c.simpleName() + " records audit events but logs through a logger named after the class: its lines mix "
                        + "with the application log")
                .suggestion("Logs through the logger \"" + cfg.logger() + "\" (policy audit.logger) instead, so the log "
                        + "pipeline can route audit lines apart (their own index, longer retention).");
    }

    /** A class named *Audit* (with no audit.emitters configured), or the class of a configured emitter. */
    private boolean auditClass(ClassInfo c) {
        if (!cfg.emitters().isEmpty()) {
            return cfg.emitters().stream().anyMatch(e -> (e.contains("#") ? e.substring(0, e.indexOf('#')) : e).equals(c.fqn()));
        }
        return c.simpleName().contains("Audit") && !c.simpleName().contains("Logger");
    }

    // ---- who and what in every record ------------------------------------------------------------

    private static final java.util.regex.Pattern ACTOR = java.util.regex.Pattern.compile(
            "(?i).*(principal|actor|user(name|id)?|currentuser|authentication|operator|initiator|performedby)$");
    private static final List<String> ACTOR_TYPES = List.of("java.security.Principal",
            "org.springframework.security.core.Authentication", "org.springframework.security.core.userdetails.UserDetails");

    /**
     * AUDIT_RECORD_INCOMPLETE (report): an audit call without the acting user or without the id of the record acted on.
     * A record that does not say who did it, or to what, proves nothing. The actor counts as present when an argument
     * comes from the security context (or is named like one: userId, actor, principal), or when the audit method reads
     * the security context itself; the target when an argument is a vocabulary id or named like one (…Id).
     */
    private void incompleteRecords() {
        Set<String> selfActor = new HashSet<>();   // audit methods (in the code) that read the security context themselves
        for (MethodInfo m : ctx.a.index.methods()) {
            if (m.decl().getBody() != null && readsSecurityContext(m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody())))) {
                selfActor.add(m.key());
            }
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    if (emitter(mi) && mi.getMethodType() != null) {
                        check(m, mi, selfActor);
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0);
        }
    }

    private void check(MethodInfo m, J.MethodInvocation mi, Set<String> selfActor) {
        List<Expression> args = mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
        boolean actor = args.stream().anyMatch(a -> actorValue(a))
                || ctx.a.index.resolve(mi.getMethodType()).stream().anyMatch(t -> selfActor.contains(t.key()));
        boolean target = args.stream().anyMatch(a -> ctx.domain.keyOf(a).isPresent() || named(a, "(?i).*(id|ids|key|code|number)$"));
        if (actor && target) {
            return;
        }
        int line = Pos.line(mi);
        if (ctx.ignored(m.file(), line, Rules.AUDIT_RECORD_INCOMPLETE)) {
            return;
        }
        String missing = !actor && !target ? "who did it and to which record" : !actor ? "who did it" : "to which record";
        ctx.order(Rules.AUDIT_RECORD_INCOMPLETE, m, line, "audit-record|" + line)
                .title("The audit record " + mi.getSimpleName() + "(…) in " + m.label() + " does not say " + missing)
                .suggestion((!actor ? "Pass the acting user (from the security context: SecurityContextHolder / the "
                        + "authenticated principal), or let the audit method read it itself. " : "")
                        + (!target ? "Pass the id of the record acted on. " : "")
                        + "A record without them proves nothing.");
    }

    private boolean actorValue(Expression a) {
        if (ACTOR_TYPES.stream().anyMatch(t -> TypeUtils.isAssignableTo(t, a.getType()))) {
            return true;
        }
        return readsSecurityContext(a.printTrimmed()) || named(a, ACTOR.pattern());
    }

    private static boolean readsSecurityContext(String code) {
        return code.contains("SecurityContextHolder") || code.contains("getUserPrincipal(") || code.contains("getRemoteUser(");
    }

    /** The argument's own name (a variable, a field, or the accessor called last) matches. */
    private static boolean named(Expression a, String regex) {
        String name = a instanceof J.Identifier id ? id.getSimpleName()
                : a instanceof J.FieldAccess fa ? fa.getSimpleName()
                : a instanceof J.MethodInvocation call ? call.getSimpleName().replaceFirst("^get", "") : null;
        return name != null && name.matches(regex);
    }

    /** Reads are not audited by default; every other entrypoint kind changes something. */
    private static boolean write(Entrypoint ep) {
        return switch (ep.kind()) {
            case HTTP -> !(ep.label().startsWith("GET ") || ep.label().startsWith("HEAD ") || ep.label().startsWith("OPTIONS "));
            case SCHEDULED, MAIN, BATCH -> false;
            default -> true;
        };
    }

    private static boolean matches(Policy.AuditAction a, Entrypoint ep, Set<String> terms) {
        if (a.http() != null && glob(a.http()).matcher(ep.label()).matches()) {
            return true;
        }
        if (a.method() != null && Pattern.compile(a.method()).matcher(ep.method().name()).matches()) {
            return true;
        }
        if (a.term() != null) {
            String t = a.term().toLowerCase(Locale.ROOT);
            String words = (ep.label() + " " + ep.method().name()).replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
            return Arrays.asList(words.split("[^a-z0-9]+")).contains(t) || Arrays.asList(words.split("[^a-z0-9]+")).contains(t + "s");
        }
        return false;
    }

    /** {@code "* /admin/**"}: * matches within a path segment (or the verb), ** across segments. */
    static Pattern glob(String g) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }

    private static String describe(Policy.AuditAction a) {
        return a.http() != null ? a.http() : a.method() != null ? "method " + a.method() : "term " + a.term();
    }

    /** The method calls an audit emitter. */
    private boolean emits(MethodInfo m) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                if (!found[0] && emitter(mi)) {
                    found[0] = true;
                }
                return found[0] ? mi : super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        return found[0];
    }

    private boolean emitter(J.MethodInvocation mi) {
        JavaType.Method mt = mi.getMethodType();
        if (mt == null) {
            return false;
        }
        String owner = mt.getDeclaringType().getFullyQualifiedName();
        if (!cfg.emitters().isEmpty()) {
            return cfg.emitters().stream().anyMatch(e -> e.contains("#")
                    ? e.equals(owner + "#" + mt.getName())
                    : e.equals(owner) || TypeUtils.isAssignableTo(e, mt.getDeclaringType()));
        }
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        if (simple.contains("Audit") && !simple.contains("Logger")) {
            return true;
        }
        if (owner.equals("org.springframework.boot.actuate.audit.AuditEventRepository")) {
            return true;
        }
        for (Expression arg : mi.getArguments()) {
            if (auditType(TypeUtils.asFullyQualified(arg.getType()), new HashSet<>())) {
                return true;   // an AuditAction value, or an event implementing AuditableDomainEvent
            }
        }
        return false;
    }

    /** The type, a supertype or an interface is named *Audit*. */
    private static boolean auditType(JavaType.FullyQualified t, Set<String> seen) {
        if (t == null || !seen.add(t.getFullyQualifiedName()) || t.getFullyQualifiedName().startsWith("java.")) {
            return false;
        }
        if (t.getClassName().contains("Audit")) {
            return true;
        }
        if (auditType(t.getSupertype(), seen)) {
            return true;
        }
        return t.getInterfaces().stream().anyMatch(i -> auditType(i, seen));
    }

    // ---- security events ------------------------------------------------------------------------

    private void securityEvents() {
        if (!ctx.a.project.probe().hasClass("org.springframework.security.authentication.event.AbstractAuthenticationEvent")) {
            return;
        }
        for (ClassInfo c : ctx.a.index.classes()) {
            if (TypeUtils.isAssignableTo("org.springframework.boot.actuate.audit.AuditEventRepository", c.type())
                    || TypeUtils.isAssignableTo("org.springframework.boot.actuate.security.AbstractAuthenticationAuditListener", c.type())) {
                return;
            }
        }
        // the service audits logins its own way: an audit-typed event or record about logins/authentication
        for (ClassInfo c : ctx.a.index.classes()) {
            if (c.simpleName().matches("(?i).*(login|logon|signin|authenticat|accessdenied).*") && auditType(c.type(), new HashSet<>())) {
                return;
            }
        }
        for (MethodInfo m : ctx.a.index.methods()) {
            boolean listener = m.hasAnnotation("org.springframework.context.event.EventListener")
                    || m.name().equals("onApplicationEvent");
            if (!listener) {
                continue;
            }
            for (Statement p : m.decl().getParameters()) {
                if (p instanceof J.VariableDeclarations vd && SECURITY_EVENTS.stream().anyMatch(e -> TypeUtils.isAssignableTo(e, vd.getType()))) {
                    return;
                }
            }
            String ann = m.decl().getLeadingAnnotations().toString();
            if (SECURITY_EVENTS.stream().anyMatch(e -> ann.contains(e.substring(e.lastIndexOf('.') + 1)))) {
                return;
            }
        }
        ClassInfo config = ctx.a.index.classes().stream()
                .filter(c -> c.file().text().contains("SecurityFilterChain")).findFirst().orElse(null);
        String file = config != null ? config.file().relPath() : ServiceYaml.path(ctx);
        int line = config != null ? dev.logast.parse.Pos.line(config.decl()) : 1;
        ctx.order(Rules.AUDIT_SECURITY_EVENTS, "security-events").at(file, line)
                .title("Spring Security is used but nothing records failed logins, successful logins or denied access: "
                        + "brute force and privilege probing leave no audit trail")
                .suggestion("Listen to AbstractAuthenticationFailureEvent, AuthenticationSuccessEvent and AuthorizationDeniedEvent "
                        + "(@EventListener) and record them through the audit emitter, or provide an AuditEventRepository bean "
                        + "(Spring Boot then records them itself). Authorization events also need an AuthorizationEventPublisher bean.");
    }
}
