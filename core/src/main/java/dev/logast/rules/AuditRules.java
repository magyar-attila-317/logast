package dev.logast.rules;

import dev.logast.model.Annotations;
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
 *   <li>AUDIT_FAILURES_UNRECORDED: the audit call only runs after the work succeeded;</li>
 *   <li>AUDIT_RECORD_INCOMPLETE: the record does not say who did it, or to which record; an audit method's records
 *       cannot say the outcome or the request (correlation id);</li>
 *   <li>AUDIT_LOGGER_UNROUTED: records on the audit logger that the logging configuration mixes in or drops;</li>
 *   <li>AUDIT_BEFORE_COMMIT: a record that leaves before the transaction commits;</li>
 *   <li>AUDIT_RECORD_SECRET: a password, token, PIN, card number or IBAN in a record;</li>
 *   <li>AUDIT_ACTOR_LOST_ASYNC: the actor read from the security context on another thread;</li>
 *   <li>AUDIT_LOG_MIXED: an audit class logs through its class logger;</li>
 *   <li>DATA_CHANGES_UNAUDITED: entities changed with nothing filling {@code @LastModifiedBy}, including bulk
 *       statements that bypass Spring Data auditing;</li>
 *   <li>AUDIT_SECURITY_EVENTS: Spring Security is used but nothing records authentication failures, successes or
 *       denied access durably (an in-memory repository, a listener without an AuthorizationEventPublisher).</li>
 * </ul>
 * An emitter is a call listed in {@code audit.emitters} ({@code com.x.AuditService} or {@code com.x.ActivityLogger#log});
 * with none listed, a call that passes something to a type whose own name says *Audit* (not a builder's step), or
 * passes a value whose own type is named *Audit* (an AuditAction enum value), or publishes an event whose type
 * hierarchy is. An entity extending {@code AbstractAuditable} handed to {@code repository.save} is no audit record (#50).
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
            recordFields();
        }
        if (ctx.enabled(Rules.AUDIT_LOG_MIXED) || ctx.enabled(Rules.AUDIT_LOGGER_UNROUTED)) {
            auditLoggers();
        }
        if (ctx.enabled(Rules.AUDIT_LOGGER_UNROUTED)) {
            unroutedLogger();
        }
        if (ctx.enabled(Rules.DATA_CHANGES_UNAUDITED)) {
            dataChanges();
        }
        if (ctx.enabled(Rules.AUDIT_BEFORE_COMMIT)) {
            beforeCommit();
        }
        if (ctx.enabled(Rules.AUDIT_RECORD_SECRET)) {
            secrets();
        }
        if (ctx.enabled(Rules.AUDIT_ACTOR_LOST_ASYNC)) {
            actorLostAsync();
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
                    .doneWhen("an audit call is reachable from it")
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
                        + "finally (with the outcome), or record the attempt before the work starts.")
                .doneWhen("an audit call it reaches sits in a catch or finally block, or comes before the other work");
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
                    if (emitter(mi)) {
                        // its own arguments (principal.getName(), order.getId()) are part of the record, not work (#50)
                        if (inCatchOrFinally()) {
                            found[0] = true;
                        } else if (work[0]) {
                            auditAfterWork[0] = true;
                        }
                        return mi;
                    }
                    J.MethodInvocation r = super.visitMethodInvocation(mi, p);   // arguments first: they run before the call
                    if (!logger(mi) && !support(mi)) {
                        work[0] = true;
                    }
                    return r;
                }

                private static Object enclosingTree(org.openrewrite.Cursor c) {
                    for (org.openrewrite.Cursor p = c.getParent(); p != null; p = p.getParent()) {
                        if (p.getValue() instanceof org.openrewrite.Tree t) {
                            return t;
                        }
                    }
                    return null;
                }

                private boolean inCatchOrFinally() {
                    for (org.openrewrite.Cursor c = getCursor(); c != null; c = c.getParent()) {
                        if (c.getValue() instanceof J.Try.Catch) {
                            return true;
                        }
                        // the finally block's direct cursor parent is the JLeftPadded around it, not the J.Try (#85)
                        if (c.getValue() instanceof J.Block b && enclosingTree(c) instanceof J.Try t && t.getFinally() == b) {
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

    // ---- who last changed a record (Spring Data auditing) -----------------------------------------

    private static final List<String> WRITING_REPOSITORIES = List.of("org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.ListCrudRepository", "org.springframework.data.repository.reactive.ReactiveCrudRepository");
    private static final String LAST_MODIFIED_BY = "org.springframework.data.annotation.LastModifiedBy";
    private static final String CREATED_BY = "org.springframework.data.annotation.CreatedBy";

    /**
     * DATA_CHANGES_UNAUDITED (report): an entity changed through a Spring Data repository with nothing recording who
     * last changed it (no {@code @LastModifiedBy}); or entities with {@code @CreatedBy / @LastModifiedBy} but no
     * {@code AuditorAware} bean to fill them, so they stay empty.
     */
    private void dataChanges() {
        Map<String, ClassInfo> entities = new LinkedHashMap<>();
        for (ClassInfo repo : ctx.a.index.classes()) {
            if (!repo.isInterface()) {
                continue;
            }
            for (JavaType.FullyQualified iface : repo.type().getInterfaces()) {
                if (iface instanceof JavaType.Parameterized p && WRITING_REPOSITORIES.stream().anyMatch(w -> TypeUtils.isAssignableTo(w, p))
                        && !p.getTypeParameters().isEmpty()) {
                    ClassInfo entity = ctx.a.index.clazz(TypeUtils.asFullyQualified(p.getTypeParameters().getFirst()) == null ? ""
                            : TypeUtils.asFullyQualified(p.getTypeParameters().getFirst()).getFullyQualifiedName());
                    if (entity != null) {
                        entities.putIfAbsent(entity.fqn(), entity);
                    }
                }
            }
        }
        boolean anyAudited = false;
        for (ClassInfo e : entities.values()) {
            boolean lastModifiedBy = hasFieldAnnotation(e, LAST_MODIFIED_BY);
            anyAudited |= lastModifiedBy || hasFieldAnnotation(e, CREATED_BY);
            if (lastModifiedBy || ctx.ignored(e.file(), Pos.line(e.decl()), Rules.DATA_CHANGES_UNAUDITED)) {
                continue;
            }
            ctx.order(Rules.DATA_CHANGES_UNAUDITED, "data-audit|" + e.fqn()).at(e.file().relPath(), Pos.line(e.decl()))
                    .title(e.simpleName() + " is changed through a Spring Data repository, but nothing records who last changed it")
                    .suggestion("Add @LastModifiedBy (and @CreatedBy, @LastModifiedDate) fields and enable auditing "
                            + "(@EnableJpaAuditing / @EnableJdbcAuditing with an AuditorAware bean that reads the current user).")
                    .doneWhen(e.simpleName() + " has a field annotated @LastModifiedBy");
        }
        boolean auditorAware = ctx.a.index.classes().stream().anyMatch(c -> TypeUtils.isAssignableTo("org.springframework.data.domain.AuditorAware", c.type()))
                || ctx.a.index.methods().stream().anyMatch(m -> m.type() != null
                && TypeUtils.isAssignableTo("org.springframework.data.domain.AuditorAware", m.type().getReturnType()));
        if (anyAudited && !auditorAware) {
            ClassInfo first = entities.values().stream().filter(e -> hasFieldAnnotation(e, LAST_MODIFIED_BY) || hasFieldAnnotation(e, CREATED_BY))
                    .findFirst().orElseThrow();
            ctx.order(Rules.DATA_CHANGES_UNAUDITED, "data-audit-auditor").at(first.file().relPath(), Pos.line(first.decl()))
                    .title("Entities have @CreatedBy / @LastModifiedBy, but no AuditorAware bean fills them: they stay empty")
                    .suggestion("Add an AuditorAware bean that returns the current user (from the security context), and enable "
                            + "auditing (@EnableJpaAuditing / @EnableJdbcAuditing).")
                    .doneWhen("a class implements AuditorAware, or a method returns one");
        }
        bulkWrites(entities.values().stream().filter(e -> hasFieldAnnotation(e, LAST_MODIFIED_BY)).toList());
    }

    /** {@code update <table>} / {@code insert into <table>} at the start of a statement (JPQL names the entity instead). */
    private static final Pattern WRITE_STATEMENT = Pattern.compile("^\\s*(update|insert\\s+into)\\s+([\\w.\"`\\[\\]]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * DATA_CHANGES_UNAUDITED, bulk writes (#92 item 4): a statement that changes rows of an audited entity (one with
     * {@code @LastModifiedBy}) without loading it: a {@code @Modifying @Query("update …")} repository method, a
     * {@code JdbcTemplate / NamedParameterJdbcTemplate} update or batchUpdate, a {@code JdbcClient.sql(…)}. Spring Data
     * fills the auditing fields only when the entity itself is saved, so these rows keep the previous editor. Not when
     * the statement sets the {@code @LastModifiedBy} column itself. Deletes are left alone: the row is gone, and Spring
     * Data auditing records no deletion either.
     */
    private void bulkWrites(List<ClassInfo> audited) {
        if (audited.isEmpty()) {
            return;
        }
        for (ClassInfo c : ctx.a.index.classes()) {
            if (!c.isInterface()) {
                continue;
            }
            for (Statement s : c.decl().getBody().getStatements()) {
                if (!(s instanceof J.MethodDeclaration md) || md.getLeadingAnnotations().stream().noneMatch(a -> a.getSimpleName().equals("Modifying"))) {
                    continue;
                }
                for (J.Annotation a : md.getLeadingAnnotations()) {
                    if (a.getSimpleName().equals("Query")) {
                        String sql = Annotations.attribute(a, "value").map(v -> dev.logast.graph.Constants.concatenated(ctx.a.index, v)).orElse(null);
                        int line = Pos.line(md);
                        bulkWrite(audited, sql, c.file(), line, () -> ctx.orderNth(Rules.DATA_CHANGES_UNAUDITED,
                                c.fqn() + "#" + md.getSimpleName(), c.file().relPath(), line, "data-audit-bulk"),
                                c.simpleName() + "." + md.getSimpleName() + " (@Modifying @Query)");
                    }
                }
            }
        }
        for (MethodInfo m : ctx.a.index.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    Expression statement = jdbcStatement(mi);
                    if (statement != null) {
                        String what = TypeUtils.asFullyQualified(mi.getMethodType().getDeclaringType()).getClassName() + "." + mi.getSimpleName();
                        int line = Pos.line(mi);
                        bulkWrite(audited, dev.logast.graph.Constants.concatenated(ctx.a.index, statement), m.file(), line,
                                () -> ctx.orderNth(Rules.DATA_CHANGES_UNAUDITED, m, line, "data-audit-bulk"), what + " in " + m.label());
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0);
        }
    }

    /** The SQL argument of a JdbcTemplate / NamedParameterJdbcTemplate update, or of {@code JdbcClient.sql}. */
    private static Expression jdbcStatement(J.MethodInvocation mi) {
        if (mi.getMethodType() == null || mi.getArguments().isEmpty() || mi.getArguments().getFirst() instanceof J.Empty) {
            return null;
        }
        JavaType.FullyQualified owner = mi.getMethodType().getDeclaringType();
        boolean template = (mi.getSimpleName().equals("update") || mi.getSimpleName().equals("batchUpdate"))
                && (TypeUtils.isAssignableTo("org.springframework.jdbc.core.JdbcOperations", owner)
                || TypeUtils.isAssignableTo("org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations", owner));
        boolean client = mi.getSimpleName().equals("sql") && TypeUtils.isAssignableTo("org.springframework.jdbc.core.simple.JdbcClient", owner);
        return template || client ? mi.getArguments().getFirst() : null;
    }

    private void bulkWrite(List<ClassInfo> audited, String sql, dev.logast.parse.SourceFileInfo file, int line,
                           java.util.function.Supplier<OrderBuilder> order, String where) {
        if (sql == null) {
            return;
        }
        java.util.regex.Matcher w = WRITE_STATEMENT.matcher(sql);
        if (!w.find()) {
            return;
        }
        String target = w.group(2).replaceAll("[\"`\\[\\]]", "");
        String table = target.substring(target.lastIndexOf('.') + 1);
        ClassInfo entity = audited.stream().filter(e -> e.simpleName().equalsIgnoreCase(table) || tableName(e).equalsIgnoreCase(table))
                .findFirst().orElse(null);
        if (entity == null || setsLastModifiedBy(entity, sql) || ctx.ignored(file, line, Rules.DATA_CHANGES_UNAUDITED)) {
            return;
        }
        String statement = Text.oneLine(sql.strip(), 120);
        order.get()
                .title(where + " changes " + entity.simpleName() + " rows without loading them (\"" + statement + "\"): Spring Data "
                        + "auditing does not run, so @LastModifiedBy " + (w.group(1).toLowerCase(Locale.ROOT).startsWith("insert")
                        ? "stays empty" : "keeps the previous editor"))
                .suggestion("Change the rows through the repository (load, change, save), or set the @LastModifiedBy column in "
                        + "the statement from the current user (the AuditorAware bean).")
                .doneWhen("the statement sets " + entity.simpleName() + "'s @LastModifiedBy column, or the change goes through save()");
    }

    /** The table an entity maps to: {@code @Table(name / value)}, else its name in snake case (Boot's default naming). */
    private static String tableName(ClassInfo e) {
        for (J.Annotation a : e.decl().getLeadingAnnotations()) {
            if (a.getSimpleName().equals("Table")) {
                Optional<String> name = Annotations.stringAttribute(a, "name", "value");
                if (name.isPresent() && !name.get().isBlank()) {
                    return name.get();
                }
            }
        }
        return snake(e.simpleName());
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** The statement names the {@code @LastModifiedBy} field or its column ({@code @Column(name)}, else snake case). */
    private static boolean setsLastModifiedBy(ClassInfo e, String sql) {
        for (Statement s : e.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd && vd.getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, LAST_MODIFIED_BY))) {
                Set<String> names = new HashSet<>();
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    names.add(v.getSimpleName());
                    names.add(snake(v.getSimpleName()));
                }
                vd.getLeadingAnnotations().stream().filter(a -> a.getSimpleName().equals("Column"))
                        .forEach(a -> Annotations.stringAttribute(a, "name", "value").ifPresent(names::add));
                for (String n : names) {
                    if (Pattern.compile("(?i)(?<![\\w])" + Pattern.quote(n) + "(?![\\w])").matcher(sql).find()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasFieldAnnotation(ClassInfo c, String annotation) {
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd && vd.getLeadingAnnotations().stream().anyMatch(a -> dev.logast.model.Annotations.is(a, annotation))) {
                return true;
            }
        }
        return false;
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
        boolean emit = ctx.enabled(Rules.AUDIT_LOG_MIXED);
        Set<String> recordMethods = recordMethods();
        for (ClassInfo c : ctx.methodsOwners()) {
            Set<String> fields = auditLoggerFields(c);
            for (MethodInfo m : ctx.a.index.methodsOf(c.fqn())) {
                for (dev.logast.model.LogCall lc : m.facts().logs) {
                    if (onAuditLogger(lc.call(), fields)) {
                        auditLoggerLines.add(new AuditLine(m, lc.level()));
                    }
                }
            }
            if (!auditClass(c)) {
                continue;
            }
            // only a class that records audit events (its methods are called as audit calls), and only its records (#45)
            List<MethodInfo> records = ctx.a.index.methodsOf(c.fqn()).stream().filter(m -> recordMethods.contains(m.key())).toList();
            if (records.isEmpty()) {
                continue;   // AuditController, AuditConfig, an AuditCleanupJob: named like audit, records nothing
            }
            List<String> diagnostics = new ArrayList<>();
            int recordLines = 0;
            int apartRecords = 0;
            boolean diagnosticsOnAudit = false;
            Set<String> auditFields = auditLoggerFields(c);
            for (MethodInfo m : ctx.a.index.methodsOf(c.fqn())) {
                for (dev.logast.model.LogCall lc : m.facts().logs) {
                    boolean onAudit = onAuditLogger(lc.call(), auditFields);
                    if (!records.contains(m) || lc.level().compareTo(dev.logast.model.Level.WARN) >= 0) {
                        diagnostics.add(m.name() + " (" + lc.level().name() + ")");
                        diagnosticsOnAudit |= onAudit;
                    } else if (onAudit) {
                        apartRecords++;
                    } else {
                        recordLines++;
                    }
                }
            }
            if (recordLines == 0 && apartRecords == 0) {
                continue;   // its records are not log lines (written to a store): the class logger carries diagnostics only
            }
            if (recordLines == 0 && !diagnosticsOnAudit) {
                continue;   // records on the audit logger, diagnostics on the class logger: already apart (#84)
            }
            if (!diagnostics.isEmpty()) {
                if (emit && !ctx.ignored(c.file(), Pos.line(c.decl()), Rules.AUDIT_LOG_MIXED)) {
                    ctx.orderNth(Rules.AUDIT_LOG_MIXED, c.fqn(), c.file().relPath(), Pos.line(c.decl()), "audit-logger-split")
                            .title(c.simpleName() + " writes audit records and diagnostics through one logger: routed apart, the "
                                    + "diagnostics would leave the application log; mixed, the records do")
                            .suggestion("Give the records their own logger (private static final Logger AUDIT = "
                                    + "LoggerFactory.getLogger(\"" + name + "\")) and keep the class logger for the diagnostics: "
                                    + String.join(", ", diagnostics.stream().distinct().limit(5).toList()) + ".")
                            .doneWhen("its records are written through a logger named \"" + name + "\" and its "
                                    + "diagnostics through the class logger");
                }
                continue;
            }
            // the AUTO rename below moves these records onto the audit logger
            for (MethodInfo m : records) {
                m.facts().logs.stream().filter(lc -> lc.level().compareTo(dev.logast.model.Level.WARN) < 0)
                        .forEach(lc -> auditLoggerLines.add(new AuditLine(m, lc.level())));
            }
            if (!emit) {
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

    /**
     * Fields of {@code c} holding the audit logger: {@code LoggerFactory.getLogger("audit")} (the policy's audit.logger,
     * or "audit"), or Lombok's {@code log} under {@code @Slf4j(topic = "audit")}.
     */
    private Set<String> auditLoggerFields(ClassInfo c) {
        Set<String> out = new HashSet<>();
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd && TypeUtils.isAssignableTo("org.slf4j.Logger", vd.getType())) {
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    if (auditLoggerCall(v.getInitializer())) {
                        out.add(v.getSimpleName());
                    }
                }
            }
        }
        for (J.Annotation a : c.decl().getLeadingAnnotations()) {
            if (a.getSimpleName().equals("Slf4j") && a.getArguments() != null && a.getArguments().stream().anyMatch(arg ->
                    arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier k && k.getSimpleName().equals("topic")
                            && auditLoggerName(as.getAssignment()))) {
                out.add("log");
            }
        }
        return out;
    }

    /** A log call whose logger (the root of the select chain) is an audit logger field or {@code getLogger("audit")}. */
    private boolean onAuditLogger(J.MethodInvocation call, Set<String> auditFields) {
        Expression root = call.getSelect();
        while (root instanceof J.MethodInvocation inner && !inner.getSimpleName().equals("getLogger")) {
            root = inner.getSelect();
        }
        String field = root instanceof J.Identifier id ? id.getSimpleName() : root instanceof J.FieldAccess fa ? fa.getSimpleName() : null;
        return field != null && auditFields.contains(field) || auditLoggerCall(root);
    }

    private boolean auditLoggerCall(Expression e) {
        return e instanceof J.MethodInvocation init && init.getSimpleName().equals("getLogger")
                && init.getArguments().size() == 1 && auditLoggerName(init.getArguments().getFirst());
    }

    private boolean auditLoggerName(Expression e) {
        return e instanceof J.Literal l && l.getValue() instanceof String n
                && (n.equalsIgnoreCase(cfg.logger()) || n.equalsIgnoreCase("audit"));
    }

    private OrderBuilder order(ClassInfo c, int line) {
        return ctx.orderNth(Rules.AUDIT_LOG_MIXED, c.fqn(), c.file().relPath(), line, "audit-logger")
                .title(c.simpleName() + " records audit events but logs through a logger named after the class: its lines mix "
                        + "with the application log")
                .suggestion("Logs through the logger \"" + cfg.logger() + "\" (policy audit.logger) instead, so the log "
                        + "pipeline can route audit lines apart (their own index, longer retention).");
    }

    /** Methods (keys) that some call in the code uses as an audit call. */
    private Set<String> recordMethods() {
        Set<String> out = new HashSet<>();
        for (MethodInfo m : ctx.a.index.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    if (mi.getMethodType() != null && emitter(mi)) {
                        ctx.a.index.resolve(mi.getMethodType()).forEach(t -> out.add(t.key()));
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0);
        }
        return out;
    }

    /** A class named *Audit* (with no audit.emitters configured), or the class of a configured emitter. */
    private boolean auditClass(ClassInfo c) {
        if (!cfg.emitters().isEmpty()) {
            return cfg.emitters().stream().anyMatch(e -> (e.contains("#") ? e.substring(0, e.indexOf('#')) : e).equals(c.fqn()));
        }
        return c.simpleName().contains("Audit") && !c.simpleName().contains("Logger");
    }

    // ---- who and what in every record ------------------------------------------------------------

    /** A name the actor goes by: only a fallback for a plain value whose origin says nothing (#50). */
    private static final String ACTOR = "(?i).*(principal|actor|user(name|id)?|currentuser|authentication|operator|initiator|performedby)";
    /** A name the record's id goes by: {@code id}, {@code pledgeId}, {@code getKey()}; not {@code paid} or {@code valid}. */
    private static final String TARGET = "(id|ids|key|code|number|uuid)|.*(Id|ID|Ids|IDs|Key|Code|Number|Uuid|UUID)";
    private static final List<String> ACTOR_TYPES = List.of("java.security.Principal",
            "org.springframework.security.core.Authentication", "org.springframework.security.core.userdetails.UserDetails",
            "org.springframework.security.core.AuthenticatedPrincipal", "org.springframework.security.oauth2.jwt.Jwt");
    private static final String AUTHENTICATION_PRINCIPAL =
            "org.springframework.security.core.annotation.AuthenticationPrincipal";
    /** Parameters whose value the client sends: whoever it names is who the client claims to be. */
    private static final List<String> CLIENT_INPUT = List.of("org.springframework.web.bind.annotation.RequestHeader",
            "org.springframework.web.bind.annotation.RequestParam", "org.springframework.web.bind.annotation.RequestBody",
            "org.springframework.web.bind.annotation.PathVariable", "org.springframework.web.bind.annotation.CookieValue",
            "org.springframework.web.bind.annotation.RequestPart", "org.springframework.web.bind.annotation.ModelAttribute");
    /** Entrypoints with no user behind them: a job, a batch step, a message, startup. */
    private static final Set<Entrypoint.Kind> NO_USER = EnumSet.of(Entrypoint.Kind.SCHEDULED, Entrypoint.Kind.BATCH,
            Entrypoint.Kind.MAIN, Entrypoint.Kind.KAFKA, Entrypoint.Kind.JMS, Entrypoint.Kind.RABBIT, Entrypoint.Kind.STEP,
            Entrypoint.Kind.ITEM, Entrypoint.Kind.SQS, Entrypoint.Kind.STREAM, Entrypoint.Kind.QUARTZ);

    /** Audit methods (in the code) that read the security context themselves. */
    private final Set<String> selfActor = new HashSet<>();
    private boolean selfActorKnown;

    private Set<String> selfActor() {
        if (!selfActorKnown) {
            selfActorKnown = true;
            for (MethodInfo m : ctx.a.index.methods()) {
                if (m.decl().getBody() != null && readsSecurityContext(body(m))) {
                    selfActor.add(m.key());
                }
            }
        }
        return selfActor;
    }

    private static String body(MethodInfo m) {
        return m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
    }

    /**
     * AUDIT_RECORD_INCOMPLETE (report): an audit call without the acting user or without the id of the record acted on.
     * A record that does not say who did it, or to what, proves nothing. The parts of a record built in the call
     * ({@code new AuditEntry(…)}, a builder chain) count as its arguments. The actor counts as present when a value
     * comes from the security context: followed back through locals and callers to a {@code Principal},
     * {@code Authentication} or {@code @AuthenticationPrincipal} value, a call on one, {@code SecurityContextHolder}, or
     * a method that reads it; or when the audit method reads it itself. A name like {@code userId} only counts for a
     * plain value (a string, a number) whose origin says nothing, e.g. a field or a library call: the fallback where
     * types and origin cannot tell. A value the client sends (a header, a parameter) gets its own finding. Paths that
     * only jobs, batches and messages reach have no user to record.
     */
    private void incompleteRecords() {
        selfActor();
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    if (emitter(mi) && mi.getMethodType() != null) {
                        check(m, mi);
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0);
        }
    }

    private void check(MethodInfo m, J.MethodInvocation mi) {
        List<Expression> args = new ArrayList<>();
        mi.getArguments().forEach(a -> parts(a, args));
        J.Annotation client = null;
        boolean actor = ctx.a.index.resolve(mi.getMethodType()).stream().anyMatch(t -> selfActor.contains(t.key())) || noUser(m);
        for (Expression a : args) {
            if (actor) {
                break;
            }
            Origin o = origin(a, m, 2);
            if (o.security()) {
                actor = true;
            } else if (plain(a.getType()) && named(a, ACTOR)) {
                if (o.client() == null) {
                    actor = true;   // named like the actor, nothing known about where it comes from: taken at its word
                } else {
                    client = o.client();
                }
            }
        }
        boolean target = args.stream().anyMatch(a -> ctx.domain.keyOf(a).isPresent()
                || named(a, TARGET) && a.getType() != JavaType.Primitive.Boolean);
        if (actor && target) {
            return;
        }
        int line = Pos.line(mi);
        if (ctx.ignored(m.file(), line, Rules.AUDIT_RECORD_INCOMPLETE)) {
            return;
        }
        String record = "The audit record " + mi.getSimpleName() + "(…) in " + m.label();
        String basis = "the audit call is " + recognised(mi);
        String done = String.join("; and ", java.util.stream.Stream.of(
                actor ? null : "an argument comes from the security context (a Principal / Authentication parameter, "
                        + "@AuthenticationPrincipal, SecurityContextHolder), or the audit method reads it itself",
                target ? null : "an argument is the record's id (a domain key, or named id, …Id, …Key, …Code, …Number)")
                .filter(Objects::nonNull).toList());
        if (!actor && client != null) {
            ctx.orderNth(Rules.AUDIT_RECORD_INCOMPLETE, m, line, "audit-record")
                    .title(record + " takes the acting user from the request (" + client.printTrimmed()
                            + "): the client can name anyone" + (target ? "" : ", and it does not say to which record"))
                    .suggestion("Record the authenticated user instead (SecurityContextHolder, a Principal / Authentication "
                            + "parameter, @AuthenticationPrincipal); the user the request names can be kept as a separate "
                            + "field. " + (!target ? "Pass the id of the record acted on. " : "") + "A record without them proves nothing.")
                    .basis(basis).doneWhen(done);
            return;
        }
        String missing = !actor && !target ? "who did it and to which record" : !actor ? "who did it" : "to which record";
        ctx.orderNth(Rules.AUDIT_RECORD_INCOMPLETE, m, line, "audit-record")
                .title(record + " does not say " + missing)
                .suggestion((!actor ? "Pass the acting user (from the security context: SecurityContextHolder / the "
                        + "authenticated principal), or let the audit method read it itself. " : "")
                        + (!target ? "Pass the id of the record acted on. " : "")
                        + "A record without them proves nothing.")
                .basis(basis).doneWhen(done);
    }

    /** The argument, and the parts of a record built in it: constructor arguments, a builder chain's arguments. */
    private static void parts(Expression a, List<Expression> out) {
        Expression v = ValueTrace.unwrap(a);
        if (v instanceof J.Empty) {
            return;
        }
        out.add(v);
        if (v instanceof J.NewClass nc && nc.getArguments() != null) {
            nc.getArguments().forEach(x -> parts(x, out));
        } else if (v instanceof J.MethodInvocation call && builtByBuilder(call)) {
            for (Expression e = call; e instanceof J.MethodInvocation step; e = step.getSelect()) {
                step.getArguments().forEach(x -> parts(x, out));
            }
        }
    }

    private static boolean builtByBuilder(J.MethodInvocation call) {
        for (Expression e = call; e instanceof J.MethodInvocation step; e = step.getSelect()) {
            if (step.getMethodType() != null && builderStep(step.getMethodType())) {
                return true;
            }
        }
        return false;
    }

    /** Only jobs, batches or messages reach the method: there is no user to record. */
    private boolean noUser(MethodInfo m) {
        List<Entrypoint> eps = ctx.a.paths.entrypointsReaching(m);
        return !eps.isEmpty() && eps.stream().allMatch(ep -> NO_USER.contains(ep.kind()));
    }

    /** Where a value comes from, as far as it tells who acts: the security context, or the client (and how). */
    private record Origin(boolean security, J.Annotation client) {
        static final Origin NONE = new Origin(false, null);
    }

    private Origin origin(Expression e, MethodInfo m, int depth) {
        J.Annotation client = null;
        for (ValueTrace.Source s : ctx.values().sources(e, m)) {
            Origin o = sourceOrigin(s, depth);
            if (o.security()) {
                return o;
            }
            client = client != null ? client : o.client();
        }
        return new Origin(false, client);
    }

    private Origin sourceOrigin(ValueTrace.Source s, int depth) {
        Expression v = s.value();
        if (actorType(v.getType()) || readsSecurityContext(v.printTrimmed())) {
            return new Origin(true, null);
        }
        if (s.param() != null) {
            if (actorType(s.param().getType()) || s.param().getLeadingAnnotations().stream()
                    .anyMatch(a -> dev.logast.model.Annotations.is(a, AUTHENTICATION_PRINCIPAL))) {
                return new Origin(true, null);
            }
            return s.param().getLeadingAnnotations().stream()
                    .filter(a -> CLIENT_INPUT.stream().anyMatch(c -> dev.logast.model.Annotations.is(a, c)))
                    .findFirst().map(a -> new Origin(false, a)).orElse(Origin.NONE);
        }
        if (v instanceof J.MethodInvocation call && call.getMethodType() != null
                && ctx.a.index.resolve(call.getMethodType()).stream().anyMatch(t -> selfActor.contains(t.key()))) {
            return new Origin(true, null);   // currentUserId(): a method that reads the security context
        }
        Expression receiver = v instanceof J.MethodInvocation call ? call.getSelect() : v instanceof J.FieldAccess fa ? fa.getTarget() : null;
        if (receiver != null && depth > 0) {
            return origin(receiver, s.in(), depth - 1);   // principal.getName(), request.userId()
        }
        return Origin.NONE;
    }

    private static boolean actorType(JavaType t) {
        return t != null && ACTOR_TYPES.stream().anyMatch(a -> TypeUtils.isAssignableTo(a, t));
    }

    /** A string, a number, a UUID or an unresolved type: a domain object named {@code user} is not the acting user. */
    private static boolean plain(JavaType t) {
        if (t == null || t instanceof JavaType.Unknown || t instanceof JavaType.Primitive) {
            return true;
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
        return fq == null || fq.getFullyQualifiedName().startsWith("java.lang.") || fq.getFullyQualifiedName().equals("java.util.UUID");
    }

    private static boolean readsSecurityContext(String code) {
        return code.contains("SecurityContextHolder") || code.contains("getUserPrincipal(") || code.contains("getRemoteUser(");
    }

    /** The argument's own name (a variable, a field, or the accessor called last) matches. */
    private static boolean named(Expression a, String regex) {
        String name = a instanceof J.Identifier id ? id.getSimpleName()
                : a instanceof J.FieldAccess fa ? fa.getSimpleName()
                : a instanceof J.MethodInvocation call ? call.getSimpleName().replaceFirst("^get(?=[A-Z])", "") : null;
        return name != null && name.matches(regex);
    }

    /** Reads are not audited by default; every other entrypoint kind changes something. */
    private static boolean write(Entrypoint ep) {
        return switch (ep.kind()) {
            case HTTP -> !(ep.label().startsWith("GET ") || ep.label().startsWith("HEAD ") || ep.label().startsWith("OPTIONS "));
            case SCHEDULED, MAIN, BATCH, STEP -> false;
            default -> true;
        };
    }

    private static boolean matches(Policy.AuditAction a, Entrypoint ep, Set<String> terms) {
        if (a.http() != null && ep.labels().stream().anyMatch(l -> glob(a.http()).matcher(l).matches())) {
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
        return recognised(mi) != null;
    }

    /** Why the call counts as an audit record (shown as the finding's basis, #57), or null when it is none. */
    private String recognised(J.MethodInvocation mi) {
        JavaType.Method mt = mi.getMethodType();
        if (mt == null) {
            return null;
        }
        String owner = mt.getDeclaringType().getFullyQualifiedName();
        if (!cfg.emitters().isEmpty()) {
            return cfg.emitters().stream().anyMatch(e -> e.contains("#")
                    ? e.equals(owner + "#" + mt.getName())
                    : e.equals(owner) || TypeUtils.isAssignableTo(e, mt.getDeclaringType())) ? "listed in audit.emitters" : null;
        }
        if (owner.equals("org.springframework.boot.actuate.audit.AuditEventRepository")) {
            return "Spring Boot's AuditEventRepository";
        }
        if (builderStep(mt) || logger(mi)) {
            return null;   // AuditEntry.builder().actor(…): a part of one record, not a record (#50)
        }
        List<Expression> args = mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
        String simple = ownName(owner);
        if (simple.contains("Audit") && !simple.contains("Logger") && !args.isEmpty()) {
            // a call that passes something to an audit type; entry.getActor() or build() records nothing
            return "a call on " + simple + ", recognised by its name (audit.emitters is empty)";
        }
        boolean publish = mt.getName().startsWith("publish");
        for (Expression arg : args) {
            JavaType.FullyQualified t = TypeUtils.asFullyQualified(arg.getType());
            if (t == null) {
                continue;
            }
            // an AuditAction value or an AuditEvent handed to a non-JDK call; an event published whose type implements
            // AuditableDomainEvent. Not an entity whose superclass is AbstractAuditable: repository.save(order) (#50)
            if (ownName(t.getFullyQualifiedName()).contains("Audit") && !owner.startsWith("java.")
                    || publish && auditType(t, new HashSet<>())) {
                return "it is given a " + ownName(t.getFullyQualifiedName()) + ", recognised by its type name (audit.emitters is empty)";
            }
        }
        return null;
    }

    /** A builder's setter, its build() or the builder() factory: returns a *Builder or the declaring type itself. */
    private static boolean builderStep(JavaType.Method mt) {
        JavaType.FullyQualified ret = TypeUtils.asFullyQualified(mt.getReturnType());
        return ret != null && (ownName(ret.getFullyQualifiedName()).endsWith("Builder")
                || ret.getFullyQualifiedName().equals(mt.getDeclaringType().getFullyQualifiedName()));
    }

    /** The type's own simple name, also for a nested class ({@code AuditEntry$Builder} is a Builder). */
    private static String ownName(String fqn) {
        return fqn.substring(Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$')) + 1);
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

    private static final String AUDIT_EVENT_REPOSITORY = "org.springframework.boot.actuate.audit.AuditEventRepository";
    private static final String IN_MEMORY_REPOSITORY = "org.springframework.boot.actuate.audit.InMemoryAuditEventRepository";
    private static final String AUTHORIZATION_EVENT_PUBLISHER = "org.springframework.security.authorization.AuthorizationEventPublisher";
    /** Profiles of a development or test environment: an in-memory repository there is what Boot recommends it for. */
    private static final Pattern DEV_PROFILE = Pattern.compile("(?i)(dev|development|local|test|it|e2e|demo)([-_.]\\w*)?");

    /**
     * AUDIT_SECURITY_EVENTS (report): Spring Security is on the classpath, but nothing records logins and denied access
     * durably. Recorded means: the service listens to Spring Security's events itself (or has an audit type about
     * logins), or Spring Boot's auditing is enabled with an {@code AuditEventRepository} bean. Boot's
     * {@code InMemoryAuditEventRepository} does not count: Boot recommends it "only for development environments"
     * (reference, Actuator, Auditing); it keeps a bounded number of events in memory and loses them on restart, so it
     * is reported where it is declared, unless only a dev/test {@code @Profile} declares it (#92). A listener for
     * authorization events with no {@code AuthorizationEventPublisher} bean hears nothing: "To listen for these events,
     * you must first publish an AuthorizationEventPublisher" (Spring Security reference, Authorization Events).
     */
    private void securityEvents() {
        if (!ctx.a.project.probe().hasClass("org.springframework.security.authentication.event.AbstractAuthenticationEvent")) {
            return;
        }
        if (ownLoginRecords()) {
            return;
        }
        List<Object[]> inMemory = new ArrayList<>();   // [file, line, what]
        for (ClassInfo c : ctx.a.index.classes()) {
            if (TypeUtils.isAssignableTo(IN_MEMORY_REPOSITORY, c.type())) {
                if (!devProfile(c.decl().getLeadingAnnotations())) {
                    inMemory.add(new Object[]{c.file(), Pos.line(c.decl()), c.simpleName() + " extends InMemoryAuditEventRepository"});
                }
            } else if (TypeUtils.isAssignableTo(AUDIT_EVENT_REPOSITORY, c.type())
                    || TypeUtils.isAssignableTo("org.springframework.boot.actuate.security.AbstractAuthenticationAuditListener", c.type())) {
                return;   // its own repository: durable as far as logast can tell
            }
        }
        for (MethodInfo m : ctx.a.index.methods()) {
            if (m.type() == null || !TypeUtils.isAssignableTo(AUDIT_EVENT_REPOSITORY, m.type().getReturnType())
                    || !m.hasAnnotation("org.springframework.context.annotation.Bean")) {
                continue;
            }
            boolean memory = TypeUtils.isAssignableTo(IN_MEMORY_REPOSITORY, m.type().getReturnType())
                    || m.decl().getBody() != null && m.decl().getBody().printTrimmed().contains("new InMemoryAuditEventRepository");
            if (!memory) {
                return;   // a bean of another repository
            }
            if (!devProfile(m.decl().getLeadingAnnotations()) && !devProfile(m.owner().decl().getLeadingAnnotations())) {
                inMemory.add(new Object[]{m.file(), m.line(), "@Bean " + m.label()});
            }
        }
        if (!inMemory.isEmpty()) {
            Object[] first = inMemory.getFirst();
            dev.logast.parse.SourceFileInfo f = (dev.logast.parse.SourceFileInfo) first[0];
            int line = (Integer) first[1];
            if (!ctx.ignored(f, line, Rules.AUDIT_SECURITY_EVENTS)) {
                ctx.order(Rules.AUDIT_SECURITY_EVENTS, "security-events-in-memory").at(f.relPath(), line)
                        .title("Logins and denied access are recorded in an InMemoryAuditEventRepository (" + first[2] + "): a "
                                + "bounded number of events in memory, lost on every restart; Spring Boot recommends it only for "
                                + "development")
                        .suggestion("Provide an AuditEventRepository that writes to durable storage (the audit store, the "
                                + "audit logger), or declare the in-memory one only under a dev/test @Profile.")
                        .doneWhen("the AuditEventRepository bean outside dev/test profiles is not an InMemoryAuditEventRepository");
            }
            return;
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
                        + "(Spring Boot then records them itself). Authorization events also need an AuthorizationEventPublisher bean.")
                .doneWhen("a listener takes Spring Security's authentication or authorization events, or an AuditEventRepository "
                        + "bean other than the in-memory one exists");
    }

    /**
     * The service records logins itself: an audit-typed event or record about logins, or a listener of Spring Security's
     * authentication events. A listener of authorization events without an AuthorizationEventPublisher bean is reported
     * (it does not cover logins, so the repository check still runs).
     */
    private boolean ownLoginRecords() {
        for (ClassInfo c : ctx.a.index.classes()) {
            if (c.simpleName().matches("(?i).*(login|logon|signin|authenticat|accessdenied).*") && auditType(c.type(), new HashSet<>())) {
                return true;
            }
        }
        MethodInfo authorizationListener = null;
        boolean logins = false;
        for (MethodInfo m : ctx.a.index.methods()) {
            boolean listener = m.hasAnnotation("org.springframework.context.event.EventListener")
                    || m.name().equals("onApplicationEvent");
            if (!listener) {
                continue;
            }
            Set<String> heard = new HashSet<>();
            for (Statement p : m.decl().getParameters()) {
                if (p instanceof J.VariableDeclarations vd) {
                    SECURITY_EVENTS.stream().filter(e -> TypeUtils.isAssignableTo(e, vd.getType())).forEach(heard::add);
                }
            }
            String ann = m.decl().getLeadingAnnotations().toString();
            SECURITY_EVENTS.stream().filter(e -> Pattern.compile("\\b" + e.substring(e.lastIndexOf('.') + 1) + "\\b").matcher(ann).find())
                    .forEach(heard::add);
            if (heard.isEmpty()) {
                continue;
            }
            logins |= heard.stream().anyMatch(e -> e.contains(".authentication."));
            if (authorizationListener == null && heard.stream().anyMatch(e -> e.contains(".authorization."))) {
                authorizationListener = m;
            }
        }
        if (authorizationListener != null && !authorizationPublisher()) {
            MethodInfo m = authorizationListener;
            if (!ctx.ignored(m.file(), m.line(), Rules.AUDIT_SECURITY_EVENTS)) {
                ctx.order(Rules.AUDIT_SECURITY_EVENTS, m, m.line(), "security-events-authorization")
                        .title(m.label() + " listens for authorization events, but no AuthorizationEventPublisher bean publishes "
                                + "them: denied access is never recorded")
                        .suggestion("Declare an AuthorizationEventPublisher bean (new SpringAuthorizationEventPublisher("
                                + "applicationEventPublisher)): Spring Security publishes authorization events only through one.")
                        .doneWhen("a class implements AuthorizationEventPublisher, or a method returns one");
            }
        }
        return logins;
    }

    private boolean authorizationPublisher() {
        return ctx.a.index.classes().stream().anyMatch(c -> TypeUtils.isAssignableTo(AUTHORIZATION_EVENT_PUBLISHER, c.type()))
                || ctx.a.index.methods().stream().anyMatch(m -> m.type() != null
                && TypeUtils.isAssignableTo(AUTHORIZATION_EVENT_PUBLISHER, m.type().getReturnType()));
    }

    /** {@code @Profile} naming only development or test profiles. */
    private static boolean devProfile(List<J.Annotation> annotations) {
        for (J.Annotation a : annotations) {
            if (a.getSimpleName().equals("Profile")) {
                List<String> names = new ArrayList<>();
                Annotations.attribute(a, "value").ifPresent(v -> {
                    List<Expression> values = v instanceof J.NewArray na && na.getInitializer() != null ? na.getInitializer() : List.of(v);
                    for (Expression x : values) {
                        if (x instanceof J.Literal l && l.getValue() instanceof String n) {
                            names.add(n.strip());
                        }
                    }
                });
                return !names.isEmpty() && names.stream().allMatch(n -> DEV_PROFILE.matcher(n).matches());
            }
        }
        return false;
    }

    // ---- audit calls, with where they sit -------------------------------------------------------

    /**
     * An audit call in the code: in a catch block (it records the failure), inside a lambda or an anonymous class
     * (deferred: a callback, a task), and its call site (async: handed to an executor).
     */
    private record AuditCall(MethodInfo m, J.MethodInvocation mi, boolean inCatch, boolean deferred, boolean async) {
        int line() {
            return Pos.line(mi);
        }
    }

    private List<AuditCall> auditCalls;

    private List<AuditCall> auditCalls() {
        if (auditCalls != null) {
            return auditCalls;
        }
        auditCalls = new ArrayList<>();
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    if (mi.getMethodType() != null && emitter(mi)) {
                        boolean inCatch = false;
                        boolean deferred = false;
                        for (org.openrewrite.Cursor c = getCursor(); c != null; c = c.getParent()) {
                            inCatch |= c.getValue() instanceof J.Try.Catch;
                            deferred |= c.getValue() instanceof J.Lambda || c.getValue() instanceof J.NewClass nc && nc.getBody() != null;
                        }
                        boolean async = m.facts().calls.stream().anyMatch(s -> s.tree() == mi && s.async());
                        auditCalls.add(new AuditCall(m, mi, inCatch, deferred, async));
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0, new org.openrewrite.Cursor(new org.openrewrite.Cursor(null, org.openrewrite.Cursor.ROOT_VALUE), m.decl()));
        }
        return auditCalls;
    }

    /** The emitter's implementations in the code (with a body); empty for a library emitter. */
    private List<MethodInfo> targets(J.MethodInvocation mi) {
        return ctx.a.index.resolve(mi.getMethodType()).stream().filter(t -> t.decl().getBody() != null).toList();
    }

    // ---- outcome and correlation id in every record (#92 item 2) --------------------------------

    /** Words of a name or a value that state an outcome: {@code outcome}, {@code status}, {@code "parcel.delete.failed"}. */
    private static final Set<String> OUTCOME_WORDS = Set.of("outcome", "result", "status", "state", "success", "successful",
            "succeeded", "failed", "failure", "error", "exception", "cause", "reason", "granted", "denied", "allowed",
            "accepted", "rejected", "refused", "aborted", "done", "ok", "completed", "attempted", "attempt", "requested",
            "started");
    /** Code that reads the current request's trace or correlation id. */
    private static final Pattern CORRELATION_READ = Pattern.compile("MDC\\.get\\(|Tracer\\b|currentSpan\\(|Span\\.current\\(|"
            + "currentTraceContext|traceparent|(?i:trace_?id|correlation_?id|request_?id|span_?id)");
    private static final Set<String> CORRELATION_WORDS = Set.of("trace", "traceparent", "correlation", "request", "span");

    /**
     * AUDIT_RECORD_INCOMPLETE, outcome and correlation id (#92 item 2): an audit method in the code whose records cannot
     * say whether the action succeeded (no parameter or record field for it, nor every caller passing one) or which
     * request it belongs to (no trace / correlation id passed, read from MDC or the tracer, or carried by a log line,
     * which holds the MDC's trace id). OWASP's Logging Cheat Sheet lists both among the attributes of every security
     * event ("Result status", "Interaction identifier"). Reported once, at the audit method; library emitters are left
     * alone (logast cannot see what they record).
     */
    private void recordFields() {
        Map<String, List<AuditCall>> byTarget = new LinkedHashMap<>();
        Map<String, MethodInfo> methods = new HashMap<>();
        for (AuditCall c : auditCalls()) {
            for (MethodInfo t : targets(c.mi())) {
                byTarget.computeIfAbsent(t.key(), k -> new ArrayList<>()).add(c);
                methods.put(t.key(), t);
            }
        }
        for (Map.Entry<String, List<AuditCall>> e : byTarget.entrySet()) {
            MethodInfo t = methods.get(e.getKey());
            List<AuditCall> calls = e.getValue();
            boolean outcome = statesOutcome(t) || calls.stream().allMatch(c -> args(c).stream().anyMatch(this::outcomeValue));
            boolean correlation = carriesCorrelation(t, 1) || calls.stream().allMatch(c -> args(c).stream()
                    .anyMatch(a -> correlationValue(a, c.m())));
            if (outcome && correlation || ctx.ignored(t.file(), t.line(), Rules.AUDIT_RECORD_INCOMPLETE)) {
                continue;
            }
            String missing = !outcome && !correlation ? "whether the action succeeded, nor which request they belong to"
                    : !outcome ? "whether the action succeeded" : "which request they belong to (no trace or correlation id)";
            ctx.orderNth(Rules.AUDIT_RECORD_INCOMPLETE, t, t.line(), "audit-record-fields")
                    .title("The audit records " + t.label() + " writes (" + calls.size() + " call" + (calls.size() == 1 ? "" : "s")
                            + ") do not say " + missing)
                    .suggestion((!outcome ? "Record the outcome (succeeded, failed, denied; with the failure's reason): an "
                            + "outcome parameter or field, filled at each call. " : "")
                            + (!correlation ? "Record the trace or correlation id of the request (MDC.get(\"traceId\"), the "
                            + "Tracer's current span), so the record leads to the request's logs and spans. " : "")
                            + "OWASP's Logging Cheat Sheet lists both among the attributes of every security event.")
                    .basis("its calls are audit calls (" + recognised(calls.getFirst().mi()) + ")")
                    .doneWhen(String.join("; and ", java.util.stream.Stream.of(
                            outcome ? null : "a parameter (or a field of the record it takes) is named or typed as an "
                                    + "outcome (outcome, status, success, a boolean, an exception, an …Outcome enum), or every "
                                    + "call passes one",
                            correlation ? null : "it, or a method it calls, reads the trace / correlation id (MDC.get, the "
                                    + "Tracer, Span.current()) or writes the record as a log line, or every call passes one")
                            .filter(Objects::nonNull).toList()));
        }
    }

    private List<Expression> args(AuditCall c) {
        List<Expression> out = new ArrayList<>();
        c.mi().getArguments().forEach(a -> parts(a, out));
        return out;
    }

    /** The audit method takes an outcome: by a parameter, a field of a record type it takes, or its own name. */
    private boolean statesOutcome(MethodInfo t) {
        if (words(t.name()).stream().anyMatch(w -> OUTCOME_WORDS.contains(w) || w.equals("succeed") || w.equals("fail")
                || w.equals("deny"))) {
            return true;
        }
        for (Statement p : t.decl().getParameters()) {
            if (p instanceof J.VariableDeclarations vd && (outcomeType(vd.getType())
                    || vd.getVariables().stream().anyMatch(v -> outcomeName(v.getSimpleName()))
                    || recordField(vd.getType(), this::outcomeName))) {
                return true;
            }
        }
        return false;
    }

    private boolean outcomeValue(Expression a) {
        if (outcomeType(a.getType())) {
            return true;
        }
        if (a instanceof J.Literal l && l.getValue() instanceof String v) {
            String[] segments = v.split("[._:\\- ]+");
            return segments.length > 0 && OUTCOME_WORDS.contains(segments[segments.length - 1].toLowerCase(Locale.ROOT));
        }
        String name = DomainValues.derivedKey(a);
        return name != null && outcomeName(name);
    }

    private boolean outcomeName(String name) {
        return words(name).stream().anyMatch(OUTCOME_WORDS::contains);
    }

    /** A boolean, an exception, or a type named …Outcome / …Result / …Status. */
    private static boolean outcomeType(JavaType t) {
        if (t == JavaType.Primitive.Boolean || TypeUtils.isOfClassType(t, "java.lang.Boolean")
                || TypeUtils.isAssignableTo("java.lang.Throwable", t)) {
            return true;
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
        return fq != null && !fq.getFullyQualifiedName().startsWith("java.")
                && ownName(fq.getFullyQualifiedName()).matches(".*(Outcome|Result|Status)");
    }

    /** A class in the code (an audit entry the method takes) has a field whose name passes. */
    private boolean recordField(JavaType t, java.util.function.Predicate<String> name) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
        ClassInfo c = fq == null ? null : ctx.a.index.clazz(fq.getFullyQualifiedName());
        if (c == null) {
            return false;
        }
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd && vd.getVariables().stream().anyMatch(v -> name.test(v.getSimpleName()))) {
                return true;
            }
        }
        // a record's components
        return c.decl().getPrimaryConstructor() != null && c.decl().getPrimaryConstructor().stream().anyMatch(p ->
                p instanceof J.VariableDeclarations vd && vd.getVariables().stream().anyMatch(v -> name.test(v.getSimpleName())));
    }

    /**
     * The audit method (or a method it calls, {@code depth} levels down) carries the request's correlation: a parameter
     * or record field for it, a read of MDC or the tracer, or the record written as a log line (the line carries the
     * MDC's trace id).
     */
    private boolean carriesCorrelation(MethodInfo t, int depth) {
        for (Statement p : t.decl().getParameters()) {
            if (p instanceof J.VariableDeclarations vd && (vd.getVariables().stream().anyMatch(v -> correlationName(v.getSimpleName()))
                    || recordField(vd.getType(), this::correlationName))) {
                return true;
            }
        }
        if (CORRELATION_READ.matcher(body(t)).find() || t.facts().logs.stream().anyMatch(lc -> lc.level().compareTo(dev.logast.model.Level.WARN) < 0)) {
            return true;
        }
        if (depth > 0) {
            for (var e : ctx.a.graph.out(t)) {
                if (e.to().decl().getBody() != null && carriesCorrelation(e.to(), depth - 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean correlationValue(Expression a, MethodInfo m) {
        String name = DomainValues.derivedKey(a);
        if (name != null && correlationName(name)) {
            return true;
        }
        return ctx.values().sources(a, m).stream().anyMatch(s -> CORRELATION_READ.matcher(s.value().printTrimmed()).find());
    }

    private boolean correlationName(String name) {
        List<String> w = words(name);
        return w.stream().anyMatch(CORRELATION_WORDS::contains) && (w.contains("id") || w.contains("traceparent")
                || w.size() == 1 && w.getFirst().equals("trace"));
    }

    private static List<String> words(String name) {
        return DomainValues.words(name);
    }

    // ---- the audit logger routed somewhere (#92 item 6) -----------------------------------------

    /** A record written through the audit logger (or moved onto it by AUDIT_LOG_MIXED), with its level. */
    private record AuditLine(MethodInfo m, dev.logast.model.Level level) {
    }

    private final List<AuditLine> auditLoggerLines = new ArrayList<>();

    /**
     * AUDIT_LOGGER_UNROUTED (report): records go to the audit logger (or AUDIT_LOG_MIXED moves them there), but the
     * logging configuration does nothing with it. With a {@code logback(-spring).xml} the service owns its appenders:
     * without a {@code <logger name="audit">} the records reach the root appenders, mixed with the application log;
     * with one that has {@code additivity="false"} and no appender-ref, or {@code level="OFF"}, they are dropped
     * (logback manual, Architecture: appender additivity). {@code logging.level.audit} above the records' level drops
     * them too (Boot reference, Logging, Log Levels). Without a logback file, Boot's structured logging writes the logger
     * name as a field of every line, which the pipeline can route on: nothing to report.
     */
    private void unroutedLogger() {
        if (auditLoggerLines.isEmpty()) {
            return;
        }
        String name = cfg.logger();
        dev.logast.model.Level lowest = auditLoggerLines.stream().map(AuditLine::level).min(Comparator.naturalOrder()).orElseThrow();
        String where = auditLoggerLines.getFirst().m().label();
        java.nio.file.Path resources = dev.logast.SpringProperties.appResources(ctx.a.project.root());
        String level = ctx.a.springProperties.get("logging.level." + name);
        if (level != null && dropped(level, lowest)) {
            String file = ServiceYaml.path(ctx);
            int line = 1;
            for (String f : resources == null ? List.<String>of() : dev.logast.SpringProperties.FILES) {
                java.nio.file.Path p = resources.resolve(f);
                int at = !java.nio.file.Files.exists(p) ? 0 : lineOf(p, f.endsWith(".properties") ? "logging.level." + name : name + ":");
                if (at > 0) {
                    file = ctx.a.project.root().relativize(p).toString().replace('\\', '/');
                    line = at;   // the last file wins, as Spring Boot reads them
                }
            }
            ctx.order(Rules.AUDIT_LOGGER_UNROUTED, "audit-logger-level").at(file, line)
                    .title("logging.level." + name + " is " + level + ", but audit records are written at "
                            + lowest.name() + " (" + where + "): they are dropped")
                    .suggestion("Remove logging.level." + name + " or set it to " + lowest.name() + " or lower: every audit "
                            + "record must reach the log.")
                    .doneWhen("logging.level." + name + " is unset, or lets " + lowest.name() + " through");
        }
        if (resources == null) {
            return;
        }
        for (String file : List.of("logback-spring.xml", "logback.xml")) {
            java.nio.file.Path xml = resources.resolve(file);
            if (!java.nio.file.Files.exists(xml)) {
                continue;
            }
            String rel = ctx.a.project.root().relativize(xml).toString().replace('\\', '/');
            org.w3c.dom.Element logger = auditLoggerElement(xml, name);
            if (logger == null) {
                ctx.order(Rules.AUDIT_LOGGER_UNROUTED, rel).at(rel, 1)
                        .title(file + " has no <logger name=\"" + name + "\">: the audit records (" + where + ") go to the root "
                                + "appenders, mixed with the application log")
                        .suggestion("Route the audit logger apart: <logger name=\"" + name + "\" level=\"INFO\" additivity=\"false\">"
                                + " with an appender-ref to an appender of its own (its own file or index, longer retention).")
                        .doneWhen(file + " declares <logger name=\"" + name + "\">");
                continue;
            }
            boolean noAppender = logger.getElementsByTagName("appender-ref").getLength() == 0;
            String loggerLevel = logger.getAttribute("level");
            boolean off = "OFF".equalsIgnoreCase(loggerLevel) || !loggerLevel.isEmpty() && dropped(loggerLevel, lowest);
            if (off || noAppender && "false".equals(logger.getAttribute("additivity"))) {
                ctx.order(Rules.AUDIT_LOGGER_UNROUTED, rel + "|dropped").at(rel, Math.max(1, lineOf(xml, "name=\"" + name + "\"")))
                        .title(file + " drops the audit records (" + where + "): the logger \"" + name + "\" "
                                + (off ? "has level " + loggerLevel : "has additivity=\"false\" and no appender-ref"))
                        .suggestion("Give the logger \"" + name + "\" an appender-ref to an appender of its own"
                                + (off ? " and a level that lets " + lowest.name() + " through" : "") + ".")
                        .doneWhen("the logger \"" + name + "\" has an appender-ref" + (off ? " and a level up to " + lowest.name() : "")
                                + ", or additivity left on");
            }
        }
    }

    private static boolean dropped(String level, dev.logast.model.Level records) {
        String l = level.strip().toUpperCase(Locale.ROOT);
        if (l.equals("OFF")) {
            return true;
        }
        try {
            return dev.logast.model.Level.valueOf(l).compareTo(records) > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The {@code <logger>} element for the audit logger or one of its parents (anywhere, e.g. in a springProfile). */
    private static org.w3c.dom.Element auditLoggerElement(java.nio.file.Path xml, String name) {
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.NodeList loggers = f.newDocumentBuilder().parse(xml.toFile()).getElementsByTagName("logger");
            for (int i = 0; i < loggers.getLength(); i++) {
                org.w3c.dom.Element e = (org.w3c.dom.Element) loggers.item(i);
                String n = e.getAttribute("name");
                if (n.equals(name) || name.startsWith(n + ".")) {
                    return e;
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int lineOf(java.nio.file.Path file, String text) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(text)) {
                    return i + 1;
                }
            }
        } catch (java.io.IOException | java.io.UncheckedIOException e) {
            // unreadable: not found
        }
        return 0;
    }

    // ---- audit record vs transaction outcome (#92 item 5) ---------------------------------------

    private static final List<String> TRANSACTIONAL = List.of("org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");
    /** Senders whose message leaves at once, whatever the database transaction does. */
    private static final List<String> MESSAGING = List.of("org.springframework.kafka.core.KafkaOperations",
            "org.springframework.amqp.core.AmqpTemplate", "org.springframework.jms.core.JmsOperations",
            "org.springframework.cloud.stream.function.StreamBridge");
    private static final List<String> HTTP = List.of("org.springframework.web.client.RestOperations",
            "org.springframework.web.client.RestClient", "java.net.http.HttpClient");

    /**
     * AUDIT_BEFORE_COMMIT (report): an audit call inside a transaction (a {@code @Transactional} method, or reached from
     * one on the same thread) whose audit method writes outside it: a log line, a message, an HTTP call, a method of its
     * own transaction (REQUIRES_NEW) or another thread. When the transaction rolls back, the record says the action
     * happened. A call in a catch block records the failure and is left alone; a record written through the same
     * database (a repository, JdbcTemplate) commits or rolls back with the action.
     */
    private void beforeCommit() {
        Map<String, MethodInfo> inTx = new HashMap<>();   // method → the @Transactional method whose transaction it runs in
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() != null && transactional(m)) {
                Deque<Map.Entry<MethodInfo, Integer>> todo = new ArrayDeque<>(List.of(Map.entry(m, 0)));
                while (!todo.isEmpty()) {
                    var e = todo.poll();
                    if (inTx.putIfAbsent(e.getKey().key(), m) != null && e.getKey() != m || e.getValue() >= 3) {
                        continue;
                    }
                    for (var edge : ctx.a.graph.out(e.getKey())) {
                        if (edge.async() == dev.logast.graph.CallGraph.Async.NONE && !requiresNew(edge.to())) {
                            todo.add(Map.entry(edge.to(), e.getValue() + 1));
                        }
                    }
                }
            }
        }
        for (AuditCall c : auditCalls()) {
            MethodInfo tx = inTx.get(c.m().key());
            if (tx == null || c.inCatch() || c.deferred() || c.async() || ctx.ignored(c.m().file(), c.line(), Rules.AUDIT_BEFORE_COMMIT)) {
                continue;
            }
            String outside = targets(c.mi()).stream().map(t -> outsideWrite(t, 1)).filter(Objects::nonNull).findFirst().orElse(null);
            if (outside == null) {
                continue;
            }
            ctx.orderNth(Rules.AUDIT_BEFORE_COMMIT, c.m(), c.line(), "audit-before-commit")
                    .title("The audit record " + c.mi().getSimpleName() + "(…) in " + c.m().label() + " is written inside the "
                            + "transaction of " + tx.label() + " but goes out at once (" + outside + "): if the transaction "
                            + "rolls back, the record says the action happened")
                    .suggestion("Record it when the outcome is known: publish an event and record it in a "
                            + "@TransactionalEventListener (phase AFTER_COMMIT, and AFTER_ROLLBACK for the failure), or "
                            + "register a TransactionSynchronization; or write the record through the transaction's own "
                            + "database, so it rolls back with the action.")
                    .basis("the audit call is " + recognised(c.mi()))
                    .doneWhen("the audit call runs after commit (a @TransactionalEventListener, TransactionSynchronization), "
                            + "sits in a catch block, or its audit method writes only through the database");
        }
    }

    private static boolean transactional(MethodInfo m) {
        List<J.Annotation> all = new ArrayList<>(m.decl().getLeadingAnnotations());
        all.addAll(m.owner().decl().getLeadingAnnotations());
        for (J.Annotation a : all) {
            if (TRANSACTIONAL.stream().anyMatch(t -> Annotations.is(a, t))) {
                String p = Annotations.rawAttribute(a, "propagation").orElse("") + Annotations.rawAttribute(a, "value").orElse("");
                return !p.contains("NOT_SUPPORTED") && !p.contains("NEVER") && !p.contains("SUPPORTS");
            }
        }
        return false;
    }

    private static boolean requiresNew(MethodInfo m) {
        return m.decl().getLeadingAnnotations().stream().anyMatch(a -> TRANSACTIONAL.stream().anyMatch(t -> Annotations.is(a, t))
                && (Annotations.rawAttribute(a, "propagation").orElse("") + Annotations.rawAttribute(a, "value").orElse(""))
                .contains("REQUIRES_NEW"));
    }

    /** What the audit method (or a method it calls) writes outside the caller's transaction; null if nothing. */
    private String outsideWrite(MethodInfo t, int depth) {
        if (t.hasAnnotation("org.springframework.scheduling.annotation.Async")) {
            return t.label() + " runs @Async";
        }
        if (requiresNew(t)) {
            return t.label() + " commits on its own (REQUIRES_NEW)";
        }
        Set<String> fields = auditLoggerFields(t.owner());
        if (t.facts().logs.stream().anyMatch(lc -> lc.level() == dev.logast.model.Level.INFO || onAuditLogger(lc.call(), fields))) {
            return "a log line in " + t.label();
        }
        for (var site : t.facts().calls) {
            JavaType.FullyQualified owner = site.type().getDeclaringType();
            if (MESSAGING.stream().anyMatch(o -> TypeUtils.isAssignableTo(o, owner)) && site.type().getName().matches("send.*|convertAndSend.*")) {
                return "a message sent by " + t.label();
            }
            if (HTTP.stream().anyMatch(o -> TypeUtils.isAssignableTo(o, owner) || owner.getFullyQualifiedName().startsWith(o + "$"))
                    && !site.type().getName().matches("builder|create|mutate.*")) {
                return "an HTTP call in " + t.label();
            }
        }
        if (depth > 0) {
            for (var e : ctx.a.graph.out(t)) {
                if (e.async() != dev.logast.graph.CallGraph.Async.NONE) {
                    return t.label() + " hands it to another thread";
                }
                String inner = e.to().decl().getBody() == null ? null : outsideWrite(e.to(), depth - 1);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    // ---- secrets in audit records (#92 item 7) --------------------------------------------------

    /**
     * AUDIT_RECORD_SECRET (report): a password, token, secret, full card number or IBAN passed to an audit call: as an
     * argument or a part of the record built in it, followed back through locals and callers as AUDIT_RECORD_INCOMPLETE
     * does; or an object whose class (in the code) holds such a field, which its toString or serialisation writes
     * into the record. The name matching is PII_IN_LOG's (whole words), with the secret terms; derived values
     * (tokenId, maskedIban, passwordHash) and constants are not secrets. Audit records are kept long and read widely.
     */
    private void secrets() {
        for (AuditCall c : auditCalls()) {
            Set<String> found = new LinkedHashSet<>();
            for (Expression a : args(c)) {
                if (ctx.domain.isSecret(a) || ctx.values().sources(a, c.m()).stream().anyMatch(s -> ctx.domain.isSecret(s.value()))) {
                    found.add(a.printTrimmed());
                } else if (!(a instanceof J.NewClass) && !(a instanceof J.MethodInvocation call && builtByBuilder(call))) {
                    String field = secretField(a.getType());
                    if (field != null) {
                        found.add(a.printTrimmed() + " (its " + field + ")");
                    }
                }
            }
            if (found.isEmpty() || ctx.ignored(c.m().file(), c.line(), Rules.AUDIT_RECORD_SECRET)) {
                continue;
            }
            ctx.orderNth(Rules.AUDIT_RECORD_SECRET, c.m(), c.line(), "audit-record-secret")
                    .title("The audit record " + c.mi().getSimpleName() + "(…) in " + c.m().label() + " holds a secret: "
                            + Text.oneLine(String.join(", ", found), 160))
                    .suggestion("Leave the secret out of the record (record that it changed, not its value), or mask it "
                            + "(the last four digits of a card or IBAN). Audit records are kept for years and read by many.")
                    .basis("the audit call is " + recognised(c.mi()))
                    .doneWhen("no argument of the audit call (nor a part of the record built in it) is named as a password, "
                            + "token, secret, card number or IBAN, nor holds a field so named");
        }
    }

    /** A field of the class (in the code, not a JDK type) named as a secret; null if none. */
    private String secretField(JavaType t) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
        ClassInfo c = fq == null || fq.getFullyQualifiedName().startsWith("java.") ? null : ctx.a.index.clazz(fq.getFullyQualifiedName());
        if (c == null) {
            return null;
        }
        List<Statement> members = new ArrayList<>(c.decl().getBody().getStatements());
        if (c.decl().getPrimaryConstructor() != null) {
            members.addAll(c.decl().getPrimaryConstructor());
        }
        for (Statement s : members) {
            if (s instanceof J.VariableDeclarations vd && !vd.hasModifier(J.Modifier.Type.Static)
                    && !vd.hasModifier(J.Modifier.Type.Transient)) {
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    if (DomainValues.isSecretName(v.getSimpleName())) {
                        return v.getSimpleName();
                    }
                }
            }
        }
        return null;
    }

    // ---- the actor on another thread (#92 item 3) -----------------------------------------------

    /**
     * AUDIT_ACTOR_LOST_ASYNC (report): the audit record takes the acting user from the security context, read on a
     * thread the request handed work to (an {@code @Async} method, an executor, {@code CompletableFuture.*Async}).
     * "By default, SecurityContextHolder uses a ThreadLocal" (Spring Security reference, Servlet Authentication
     * Architecture), and "when work is done on a new Thread, the SecurityContext is lost" (Concurrency Support): the
     * record names no one, or whoever last ran on a pooled thread. Not reported when the context travels: a
     * DelegatingSecurityContext* executor or runnable, a TaskDecorator that copies it, or Micrometer context
     * propagation with Spring Security's SecurityContextHolderThreadLocalAccessor (6.5+) on the classpath and a
     * propagating decorator or executor in use. Reading the user before the hop and passing it on is fine.
     */
    private void actorLostAsync() {
        if (securityContextTravels()) {
            return;
        }
        for (AuditCall c : auditCalls()) {
            if (noUser(c.m())) {
                continue;
            }
            boolean emitterReads = ctx.a.index.resolve(c.mi().getMethodType()).stream().anyMatch(t -> selfActor().contains(t.key()));
            String hop;
            if (c.async()) {
                // the audit call itself is the task: only a read inside it happens on the other thread
                hop = emitterReads || readsHere(c, true) ? "the task " + c.m().label() + " hands to an executor" : null;
            } else {
                hop = emitterReads || readsHere(c, false) ? offThread(c.m()) : null;
            }
            if (hop == null || ctx.ignored(c.m().file(), c.line(), Rules.AUDIT_ACTOR_LOST_ASYNC)) {
                continue;
            }
            ctx.orderNth(Rules.AUDIT_ACTOR_LOST_ASYNC, c.m(), c.line(), "audit-actor-async")
                    .title("The audit record " + c.mi().getSimpleName() + "(…) in " + c.m().label() + " reads the acting user from "
                            + "the security context on another thread (" + hop + "): Spring Security keeps it per thread, so "
                            + "the record names no one, or the user who last ran on that pooled thread")
                    .suggestion("Read the user on the request thread and pass it along (a parameter of the async method or "
                            + "task), or let the context travel: DelegatingSecurityContextAsyncTaskExecutor / "
                            + "DelegatingSecurityContextExecutor around the executor. MODE_INHERITABLETHREADLOCAL only "
                            + "reaches threads the request creates, not pooled ones.")
                    .basis("the audit call is " + recognised(c.mi()))
                    .doneWhen("the actor is read before the hand-over and passed in, or a DelegatingSecurityContext* "
                            + "executor / runnable (or a TaskDecorator copying the SecurityContext) is used");
        }
    }

    /** The audit call's own arguments read the security context in its method (inline, if {@code inline}). */
    private boolean readsHere(AuditCall c, boolean inline) {
        for (Expression a : args(c)) {
            for (ValueTrace.Source s : ctx.values().sources(a, c.m())) {
                if (s.in() != c.m()) {
                    continue;   // read by a caller and passed in: on the caller's thread
                }
                boolean read = readsSecurityContext(s.value().printTrimmed()) || s.value() instanceof J.MethodInvocation call
                        && call.getMethodType() != null && ctx.a.index.resolve(call.getMethodType()).stream()
                        .anyMatch(t -> selfActor().contains(t.key()));
                if (read && (!inline || Pos.start(s.value()) >= Pos.start(c.mi()) && Pos.end(s.value()) <= Pos.end(c.mi()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** How the method is reached on another thread (a same-thread chain from an async hand-over); null if never. */
    private String offThread(MethodInfo m) {
        Deque<Map.Entry<MethodInfo, Integer>> todo = new ArrayDeque<>(List.of(Map.entry(m, 0)));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            var e = todo.poll();
            if (!seen.add(e.getKey().key()) || e.getValue() > 4) {
                continue;
            }
            for (var edge : ctx.a.graph.in(e.getKey())) {
                if (edge.async() != dev.logast.graph.CallGraph.Async.NONE) {
                    return edge.to().hasAnnotation("org.springframework.scheduling.annotation.Async")
                            ? "@Async " + edge.to().label() + ", called from " + edge.from().label()
                            : edge.from().label() + " runs " + edge.to().label() + " on an executor";
                }
                todo.add(Map.entry(edge.from(), e.getValue() + 1));
            }
        }
        return null;
    }

    /** The SecurityContext is carried to other threads somewhere in the service. */
    private boolean securityContextTravels() {
        var probe = ctx.a.project.probe();
        boolean propagationLibrary = probe.hasClass("org.springframework.security.core.context.SecurityContextHolderThreadLocalAccessor")
                && probe.hasClass("io.micrometer.context.ContextSnapshot");
        boolean logastSupport = probe.hasClass("dev.logast.support.ContextPropagation");
        for (ClassInfo c : ctx.a.index.classes()) {
            String text = c.file().text();
            if (text.contains("DelegatingSecurityContext")
                    || TypeUtils.isAssignableTo("org.springframework.core.task.TaskDecorator", c.type()) && readsSecurityContext(text)
                    || propagationLibrary && (logastSupport || c.fqn().startsWith(ctx.a.supportPackage + ".")
                    || text.contains("ContextPropagatingTaskDecorator") || text.contains("ContextSnapshot"))) {
                return true;
            }
        }
        return false;
    }
}
