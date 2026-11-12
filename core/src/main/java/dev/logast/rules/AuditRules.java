package dev.logast.rules;

import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
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
        if (ctx.enabled(Rules.AUDIT_MISSING)) {
            missing();
        }
        if (ctx.enabled(Rules.AUDIT_SECURITY_EVENTS)) {
            securityEvents();
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
            if (action == null || ctx.a.paths.reachableFrom(ep).stream().anyMatch(emitting::contains)) {
                continue;
            }
            MethodInfo m = ep.method();
            if (ctx.ignored(m.file(), m.line(), Rules.AUDIT_MISSING)) {
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
