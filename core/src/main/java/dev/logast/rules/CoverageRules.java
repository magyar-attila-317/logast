package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.Annotations;
import dev.logast.model.Entrypoint;
import dev.logast.model.Level;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.orders.LogCoverage;
import dev.logast.orders.Slot;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.stream.Stream;

/**
 * Log coverage: the events that should leave a log line but don't. Each finding adds the line (NEEDS TEXT: logast
 * picks place, level and values, someone writes the sentence), and the counts form the service's log coverage.
 * <ul>
 *   <li>JOB_OUTCOME_MISSING: a job, listener or batch runner without a "started" or an outcome line; the completion
 *       line carries counts (loops it ran, the size or value it returns: {@code return compute();} becomes
 *       {@code var result = compute(); log..; return result;});</li>
 *   <li>SKIP_UNLOGGED: an early {@code return} in a job (or what it calls directly) that says nothing: why did nothing
 *       happen?</li>
 *   <li>STATE_CHANGE_UNLOGGED: a repository save/delete in a method that logs nothing;</li>
 *   <li>BATCH_SUMMARY_MISSING: a loop doing work on a job path with no summary line after it.</li>
 * </ul>
 * HTTP requests are left out of the job rules: access logs and http.server.requests cover them.
 */
final class CoverageRules {
    private static final Set<Entrypoint.Kind> JOBS = EnumSet.of(Entrypoint.Kind.SCHEDULED, Entrypoint.Kind.BATCH,
            Entrypoint.Kind.KAFKA, Entrypoint.Kind.JMS, Entrypoint.Kind.RABBIT, Entrypoint.Kind.EVENT,
            Entrypoint.Kind.CUSTOM, Entrypoint.Kind.WEBSOCKET);
    private static final Set<String> REPO_WRITES = Set.of("save", "saveAll", "saveAndFlush", "saveAllAndFlush", "delete",
            "deleteById", "deleteAll", "deleteAllById", "deleteAllInBatch");
    private static final Set<String> EM_WRITES = Set.of("persist", "merge", "remove");

    private final RuleContext ctx;
    private final Map<String, int[]> parts = new LinkedHashMap<>();

    CoverageRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    LogCoverage run() {
        Set<String> runMethods = new HashSet<>();      // scheduled / batch runs (INFO for their skips)
        Set<String> jobMethods = new HashSet<>();      // entrypoint methods of jobs/listeners
        Set<String> nearJob = new HashSet<>();         // ... and what they call directly
        Set<String> jobPaths = new HashSet<>();        // everything reachable from a job
        Set<String> anyPath = new HashSet<>();
        for (Entrypoint ep : ctx.a.entrypoints) {
            anyPath.addAll(ctx.a.paths.reachableFrom(ep));
            if (JOBS.contains(ep.kind()) && !ep.method().owner().fqn().startsWith(ctx.a.supportPackage + ".")) {
                jobMethods.add(ep.method().key());
                if (ep.kind() == Entrypoint.Kind.SCHEDULED || ep.kind() == Entrypoint.Kind.BATCH) {
                    runMethods.add(ep.method().key());
                }
                nearJob.add(ep.method().key());
                ctx.a.graph.out(ep.method()).forEach(e -> nearJob.add(e.to().key()));
                jobPaths.addAll(ctx.a.paths.reachableFrom(ep));
            }
        }
        for (Entrypoint ep : ctx.a.entrypoints) {
            if (jobMethods.contains(ep.method().key()) && ep.method().decl().getBody() != null) {
                jobOutcome(ep);
            } else if (ep.kind() == Entrypoint.Kind.HTTP) {
                count("http", logsAtInfo(ctx.a.paths.reachableFrom(ep)));
            }
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            boolean command = m.type().getReturnType() == JavaType.Primitive.Void;
            if (nearJob.contains(m.key()) && command) {
                skips(m, runMethods.contains(m.key()));   // a value-returning method's early return is logic, not a skip
            }
            if (anyPath.contains(m.key())) {
                stateChange(m);
            }
            if (jobPaths.contains(m.key()) && !jobMethods.contains(m.key()) && (command || countResult(m))) {
                loops(m);
            }
        }
        return new LogCoverage(parts);
    }

    /** Methods that read or write outside the process: repositories, HTTP clients, messaging. */
    private final Map<String, Boolean> io = new HashMap<>();
    private static final List<String> IO_TYPES = List.of("org.springframework.data.repository.Repository",
            "jakarta.persistence.EntityManager", "org.springframework.web.client.RestClient", "org.springframework.web.client.RestTemplate",
            "org.springframework.web.reactive.function.client.WebClient", "org.springframework.kafka.core.KafkaTemplate",
            "org.springframework.jms.core.JmsTemplate", "org.springframework.amqp.core.AmqpTemplate", "java.net.http.HttpClient",
            "org.springframework.jdbc.core.JdbcTemplate", "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate");

    private static boolean ioCall(JavaType.Method t) {
        return t != null && IO_TYPES.stream().anyMatch(x -> TypeUtils.isAssignableTo(x, t.getDeclaringType()));
    }

    /** The method does I/O itself or through a method it calls (two levels). */
    private boolean doesIo(MethodInfo m, int depth) {
        Boolean known = io.get(m.key());
        if (known != null) {
            return known;
        }
        boolean r = m.facts().calls.stream().anyMatch(cs -> ioCall(cs.type()))
                || depth > 0 && ctx.a.graph.out(m).stream().anyMatch(e -> doesIo(e.to(), depth - 1));
        if (depth == 2) {
            io.put(m.key(), r);
        }
        return r;
    }

    private static boolean countResult(MethodInfo m) {
        JavaType r = m.type().getReturnType();
        return r == JavaType.Primitive.Int || r == JavaType.Primitive.Long || TypeUtils.isOfClassType(r, "java.lang.Integer")
                || TypeUtils.isOfClassType(r, "java.lang.Long");
    }

    private void count(String part, boolean covered) {
        int[] c = parts.computeIfAbsent(part, k -> new int[2]);
        c[1]++;
        if (covered) {
            c[0]++;
        }
    }

    private boolean logsAtInfo(Set<String> methodKeys) {
        return methodKeys.stream().map(ctx.a.index::method).filter(Objects::nonNull)
                .anyMatch(x -> x.facts().logs.stream().anyMatch(l -> l.level().compareTo(Level.INFO) >= 0));
    }

    /**
     * Lifecycle lines recognised by their role, not their words: an INFO+ log that is the first statement of the
     * method's (innermost) body is its "started" line; one that is the last statement, also inside
     * {@code AfterCommit.log(() -> ...)}, is its "completed" line. A person may reword a generated line freely; the
     * rule that added it still sees it there and does not add another.
     */
    static Set<String> roles(MethodInfo m) {
        Set<String> out = new HashSet<>();
        if (m.decl().getBody() == null) {
            return out;
        }
        List<Statement> stmts = innermost(m.decl().getBody()).getStatements();
        if (stmts.size() < 2) {
            return out;   // a lone line is first and last at once: its wording decides
        }
        if (infoLogIn(m, stmts.getFirst())) {
            out.add("started");
        }
        Statement last = stmts.getLast();
        if (last instanceof J.Return && stmts.size() > 1) {
            last = stmts.get(stmts.size() - 2);   // the line before the final return
        }
        if (last != stmts.getFirst() && infoLogIn(m, last)) {
            out.add("completed");
        }
        return out;
    }

    /** The statement is an INFO+ log call (directly, or the line inside an AfterCommit.log lambda), not in a catch. */
    private static boolean infoLogIn(MethodInfo m, Statement s) {
        if (!(s instanceof J.MethodInvocation)) {
            return false;
        }
        int from = Pos.start(s);
        int to = Pos.end(s);
        return m.facts().logs.stream().anyMatch(lc -> lc.inCatch() == null && lc.level().compareTo(Level.INFO) >= 0
                && Pos.start(lc.call()) >= from && Pos.end(lc.call()) <= to);
    }

    private String event(LogCall lc) {
        String text = MessageTemplate.text(lc.template());
        if (text == null) {
            return null;
        }
        String plain = MessageTemplate.compliantKeys(text) != null ? MessageTemplate.compliantBase(text) : MessageTemplate.stripPlaceholders(text);
        return ctx.phrasing.eventOf(plain);
    }

    // ---- JOB_OUTCOME_MISSING --------------------------------------------------------------------

    private void jobOutcome(Entrypoint ep) {
        MethodInfo m = ep.method();
        // who owns which line is decided in one place (LifecyclePlan); this rule adds what the plan says is missing
        LifecyclePlan.Flow flow = ctx.lifecycle().flow(ep);
        boolean run = flow.kind() == LifecyclePlan.Kind.RUN;
        count("jobs", (!run || flow.started()) && flow.outcome());
        boolean addStart = flow.missing().contains("started");
        boolean addOutcome = flow.missing().contains("completed");
        if (!addStart && !addOutcome || !ctx.enabled(Rules.JOB_OUTCOME_MISSING) || ctx.ignored(m.file(), m.line(), Rules.JOB_OUTCOME_MISSING)) {
            return;
        }
        SourceFileInfo f = m.file();
        String text = f.text();
        J.Block block = innermost(m.decl().getBody());
        int[] inner = JavaEdits.blockInner(text, block);
        JavaEdits.LoggerPlan logger = ctx.logger(m.owner());
        if (inner == null || logger.name() == null || block.getStatements().isEmpty()) {
            return;
        }
        String indent = Text.indentAt(text, Pos.start(block.getStatements().getFirst()));
        OrderBuilder b = ctx.order(Rules.JOB_OUTCOME_MISSING, m, m.line(), "outcome")
                .title(ep.label() + " has no " + (addStart && addOutcome ? "'started' and no outcome log: " : addStart ? "'started' log: "
                        : "outcome log: ")
                        + (run ? "a run that did nothing looks the same as a run that never happened"
                        : "nothing shows that the message was handled, or what it changed"))
                .context(ctx.context(m, inner[0], Text.oneLine(ep.label(), 160), "MSG"));
        b.edits(logger.edits());
        // generated wording from the job's name, applied right away and marked for rewording (MESSAGE_SUGGESTED)
        String subject = jobSubject(m);
        Edit startEdit = addStart ? Edit.insert(f.relPath(), inner[0], indent + logger.name() + "."
                + ctx.policy.logging().entryLevel() + "(" + Text.javaString(phrase("started", subject)) + "); " + DbRules.WORDING_MARKER + "\n")
                : null;
        if (addOutcome) {
            Statement last = block.getStatements().getLast();
            List<String[]> values = new ArrayList<>();   // {key, expression}
            for (Statement s : block.getStatements()) {
                if (s instanceof J.ForEachLoop loop && countable(loop.getControl().getIterable())) {
                    String counted = counted(b, m, loop);
                    values.add(new String[]{key(countKeyFor(loop.getControl().getIterable(), counted)), counted + ".size()"});
                }
            }
            String completeLine = "{{MSG}}";
            if (last instanceof J.Return ret && ret.getExpression() != null) {
                insertBeforeReturn(b, m, ret, values, logger.name(), "info", completeLine);
            } else if (!(last instanceof J.Throw)) {
                b.edit(Edit.insert(f.relPath(), inner[1], indent + logLine(logger.name(), "info", completeLine, values) + "\n"));
            } else {
                ctx.drop(b);
                return;
            }
            // the keys of the values become the [k={}] suffix; the line is marked for rewording
            String done = phrase("completed", subject) + MessageTemplate.suffix(values.stream().map(v -> v[0]).toList());
            b.rewriteEdits("\"{{MSG}}\"", Text.javaString(done)).rewriteEdits("{{ARGS}});", "); " + DbRules.WORDING_MARKER);
            // in a transaction the run's result is only true once it commits: log it then (dropped on rollback)
            boolean transactional = Stream.of(m.decl().getLeadingAnnotations(), m.owner().decl().getLeadingAnnotations())
                    .flatMap(List::stream).anyMatch(an -> Annotations.is(an, "org.springframework.transaction.annotation.Transactional")
                            || Annotations.is(an, "jakarta.transaction.Transactional"));
            if (transactional && ctx.a.project.probe().hasClass("org.springframework.transaction.support.TransactionSynchronizationManager")
                    && ctx.supportHas("AfterCommit")) {
                String call = logger.name() + ".info(" + Text.javaString(done);
                b.rewriteEdits(call, "AfterCommit.log(() -> " + call).rewriteEdits("); " + DbRules.WORDING_MARKER, ")); " + DbRules.WORDING_MARKER);
                b.edits(JavaEdits.imports(f, ctx.support("AfterCommit")));
                ctx.dependOnStarter(b);
            }
        }
        if (startEdit != null) {
            b.edit(startEdit);   // added last: at the same offset as an extracted variable it then lands first
        }
    }

    /**
     * The variable to count a loop's items with. A loop over a call ({@code for (Order o : repository.findAll())}) gets
     * the call extracted first ({@code var orders = repository.findAll();}): calling it again to log would run it twice.
     */
    private String counted(OrderBuilder b, MethodInfo m, J.ForEachLoop loop) {
        Expression it = loop.getControl().getIterable();
        SourceFileInfo f = m.file();
        if (it instanceof J.Identifier || it instanceof J.FieldAccess) {
            return src(f, it);
        }
        String loopVar = loop.getControl().getVariable() instanceof J.VariableDeclarations vd
                ? vd.getVariables().getFirst().getSimpleName() : "item";
        String var = EntrypointRules.unique(DomainValues.singular(loopVar) + "s", ctx.taken(m, Pos.start(loop)));
        String text = f.text();
        int lineStart = Text.lineStart(text, Pos.start(loop));
        b.edit(Edit.insert(f.relPath(), lineStart, Text.indentAt(text, Pos.start(loop)) + "var " + var + " = " + src(f, it) + ";\n"));
        b.edit(Edit.replace(f.relPath(), Pos.start(it), Pos.end(it), var));
        return var;
    }

    /** {@code return expr;} → {@code var result = expr; log..(result); return result;} (or log the plain variable). */
    private void insertBeforeReturn(OrderBuilder b, MethodInfo m, J.Return ret, List<String[]> values, String logger, String level,
                                    String slotRef) {
        SourceFileInfo f = m.file();
        String text = f.text();
        Expression e = ret.getExpression();
        String indent = Text.indentAt(text, Pos.start(ret));
        int[] lines = JavaEdits.statementLines(text, ret);
        String var;
        String prefix = "";
        if (e instanceof J.Identifier id) {
            var = id.getSimpleName();
        } else {
            var = EntrypointRules.unique("result", ctx.taken(m, Pos.start(ret)));
            prefix = indent + "var " + var + " = " + src(f, e) + ";\n";
        }
        String[] value = resultValue(e, var);
        List<String[]> all = new ArrayList<>(values);
        if (value != null) {
            all.add(value);
        }
        String replacement = prefix + indent + logLine(logger, level, slotRef, all) + "\n" + indent + "return " + var + ";\n";
        b.edit(Edit.replace(f.relPath(), lines[0], lines[1], replacement));
        values.clear();
        values.addAll(all);
    }

    /** What to log about a returned value: a collection's size, a number, a boolean or a string; objects only by id. */
    private String[] resultValue(Expression e, String var) {
        JavaType t = e.getType();
        String base = e instanceof J.MethodInvocation mi ? mi.getSimpleName().replaceFirst("^(get|find|load|fetch|read|calculate|compute|build)", "")
                : e instanceof J.Identifier id ? id.getSimpleName() : "result";
        base = base.isEmpty() ? "result" : Text.lowerFirst(base);
        if (t == null) {
            return null;
        }
        if (TypeUtils.isAssignableTo("java.util.Collection", t) || TypeUtils.isAssignableTo("java.util.Map", t)) {
            return new String[]{key(DomainValues.countKey(base)), var + ".size()"};
        }
        if (t instanceof JavaType.Primitive || TypeUtils.isAssignableTo("java.lang.Number", t) || TypeUtils.isString(t)
                || TypeUtils.isOfClassType(t, "java.lang.Boolean")) {
            return new String[]{key(base), var};
        }
        return idOf(t, var);   // an entity / record: its id
    }

    /** {key, expression} for the id of a project type held in {@code holder}: getId()/id()/getUuid()/uuid(), record components too. */
    private String[] idOf(JavaType t, String holder) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
        var c = fq == null ? null : ctx.a.index.clazz(fq.getFullyQualifiedName());
        if (c == null) {
            return null;
        }
        String accessor = null;
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.MethodDeclaration md && Set.of("getId", "id", "getUuid", "uuid").contains(md.getSimpleName())
                    && md.getParameters().stream().allMatch(p -> p instanceof J.Empty)) {
                accessor = md.getSimpleName();
            }
        }
        if (accessor == null && c.decl().getKind() == J.ClassDeclaration.Kind.Type.Record && c.decl().getPrimaryConstructor() != null) {
            for (Statement p : c.decl().getPrimaryConstructor()) {
                if (p instanceof J.VariableDeclarations vd) {
                    for (var v : vd.getVariables()) {
                        if (Set.of("id", "uuid").contains(v.getSimpleName())) {
                            accessor = v.getSimpleName();
                        }
                    }
                }
            }
        }
        return accessor == null ? null : new String[]{key(Text.lowerFirst(fq.getClassName()) + "Id"), holder + "." + accessor + "()"};
    }

    private String logLine(String logger, String level, String slotRef, List<String[]> values) {
        return logger + "." + level + "(\"" + slotRef + "\"" + String.join("", values.stream().map(v -> ", " + v[1]).toList()) + "{{ARGS}});";
    }

    private Slot slot(String describe, List<String> keys, String event) {
        String pattern = event == null ? null : ctx.phrasing.canonicalOf(event);
        return new Slot(describe, keys, ctx.glossary.terms(), null, pattern == null ? null : event, pattern);
    }

    /** Inside the scopes logast wraps entrypoints in (MdcScope / ObservedContext), so the line keeps the context. */
    static J.Block innermost(J.Block block) {
        while (true) {
            List<Statement> s = block.getStatements();
            if (s.size() == 1 && s.getFirst() instanceof J.Try t && t.getResources() != null) {
                block = t.getBody();
            } else if (s.size() == 2 && s.getFirst() instanceof J.VariableDeclarations vd
                    && vd.getTypeExpression() != null && vd.getTypeExpression().printTrimmed().endsWith("ObservedContext")
                    && s.get(1) instanceof J.Try t) {
                block = t.getBody();
            } else {
                return block;
            }
        }
    }

    // ---- SKIP_UNLOGGED --------------------------------------------------------------------------

    private void skips(MethodInfo m, boolean entrypoint) {
        SourceFileInfo f = m.file();
        String text = f.text();
        List<J.If> found = new ArrayList<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda l, Integer p) {
                return l;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.If visitIf(J.If i, Integer p) {
                if (i.getElsePart() == null && earlyReturn(i.getThenPart()) != null) {
                    found.add(i);
                }
                return super.visitIf(i, p);
            }
        }.visit(m.decl().getBody(), 0);
        JavaEdits.LoggerPlan logger = ctx.logger(m.owner());
        int n = 0;
        for (J.If i : found) {
            boolean logged = m.facts().logs.stream().anyMatch(l -> Pos.start(l.call()) >= Pos.start(i.getThenPart())
                    && Pos.start(l.call()) < Pos.end(i.getThenPart()));
            count("skips", logged);
            int line = Pos.line(i);
            if (logged || logger.name() == null || !ctx.enabled(Rules.SKIP_UNLOGGED) || ctx.ignored(f, line, Rules.SKIP_UNLOGGED)) {
                continue;
            }
            J.Return ret = earlyReturn(i.getThenPart());
            String level = entrypoint ? "info" : "debug";
            String indentIf = Text.indentAt(text, Pos.start(i));
            String unit = Text.indentUnit(text);
            String log = logLine(logger.name(), level, "{{MSG}}", List.of());
            String retSrc = text.substring(Pos.start(ret), JavaEdits.statementEnd(text, ret));
            OrderBuilder b = ctx.order(Rules.SKIP_UNLOGGED, m, line, "skip" + n++)
                    .title("'" + Text.oneLine(i.getIfCondition().printTrimmed(), 60) + "' ends " + m.label()
                            + " early without a log: nobody can tell why nothing happened")
                    .context(ctx.context(m, Pos.start(i), Text.oneLine(i.printTrimmed(), 160), "MSG"));
            b.edits(logger.edits());
            J then = i.getThenPart() instanceof J.Block blk ? blk : ret;
            int start = then instanceof J.Block ? Pos.start(then) : Pos.start(ret);
            int end = then instanceof J.Block ? Pos.end(then) : JavaEdits.statementEnd(text, ret);
            b.edit(Edit.replace(f.relPath(), start, end, "{\n" + indentIf + unit + log + "\n" + indentIf + unit + retSrc + "\n" + indentIf + "}"));
            b.slot("MSG", slot("Why " + m.label() + " stops here, in domain terms, e.g. 'Invoice export skipped, no open invoices'",
                    List.of(), "skipped"));
        }
    }

    /** The then-part is just {@code return;} / {@code return <constant or empty value>;} (alone, or in a block). */
    private static J.Return earlyReturn(Statement then) {
        Statement s = then instanceof J.Block b && b.getStatements().size() == 1 ? b.getStatements().getFirst() : then;
        if (!(s instanceof J.Return r)) {
            return null;
        }
        Expression e = r.getExpression();
        boolean trivial = e == null || e instanceof J.Literal || e instanceof J.MethodInvocation mi
                && Set.of("of", "empty", "emptyList", "emptySet", "emptyMap", "none", "noContent", "ok").contains(mi.getSimpleName())
                && mi.getArguments().stream().allMatch(a -> a instanceof J.Empty);
        return trivial ? r : null;
    }

    // ---- STATE_CHANGE_UNLOGGED ------------------------------------------------------------------

    private void stateChange(MethodInfo m) {
        J.MethodInvocation[] write = {null};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda l, Integer p) {
                return l;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                JavaType.Method mt = mi.getMethodType();
                if (write[0] == null && mt != null && (REPO_WRITES.contains(mt.getName())
                        && TypeUtils.isAssignableTo("org.springframework.data.repository.Repository", mt.getDeclaringType())
                        || EM_WRITES.contains(mt.getName()) && TypeUtils.isAssignableTo("jakarta.persistence.EntityManager", mt.getDeclaringType()))) {
                    write[0] = mi;
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        if (write[0] == null) {
            return;
        }
        boolean logged = m.facts().logs.stream().anyMatch(l -> l.level().compareTo(Level.INFO) >= 0);
        count("stateChanges", logged);
        J.MethodInvocation mi = write[0];
        int line = Pos.line(mi.getName());
        if (logged || !ctx.enabled(Rules.STATE_CHANGE_UNLOGGED) || ctx.ignored(m.file(), line, Rules.STATE_CHANGE_UNLOGGED)) {
            return;
        }
        JavaEdits.LoggerPlan logger = ctx.logger(m.owner());
        J stmt = enclosingStatement(m, mi);
        if (logger.name() == null || stmt == null) {
            return;
        }
        SourceFileInfo f = m.file();
        String text = f.text();
        List<String[]> values = new ArrayList<>();
        Expression arg = mi.getArguments().isEmpty() || mi.getArguments().getFirst() instanceof J.Empty ? null : mi.getArguments().getFirst();
        OrderBuilder b = ctx.order(Rules.STATE_CHANGE_UNLOGGED, m, line, "state")
                .title(m.label() + " " + mi.getSimpleName() + "s data but logs nothing: the change leaves no trace in the logs")
                .context(ctx.context(m, Pos.start(stmt), Text.oneLine(stmt.printTrimmed(), 160), "MSG"));
        b.edits(logger.edits());
        if (stmt instanceof J.Return ret && ret.getExpression() == mi) {
            if (arg != null) {
                values.addAll(identity(arg, null));
            }
            insertBeforeReturn(b, m, ret, values, logger.name(), "info", "{{MSG}}");
        } else {
            String subject = stmt instanceof J.VariableDeclarations vd ? vd.getVariables().getFirst().getSimpleName() : null;
            if (arg != null) {
                values.addAll(identity(arg, subject));
            }
            int[] lines = JavaEdits.statementLines(text, stmt);
            b.edit(Edit.insert(f.relPath(), lines[1], Text.indentAt(text, Pos.start(stmt)) + logLine(logger.name(), "info", "{{MSG}}", values) + "\n"));
        }
        b.slot("MSG", slot("What changed, in domain terms, e.g. 'Order cancelled' or 'Shift closed'",
                values.stream().map(v -> v[0]).toList(), null));
    }

    /** The values that identify what was written: an entity's id, a collection's size, or the id passed in. */
    private List<String[]> identity(Expression arg, String savedVar) {
        JavaType t = arg.getType();
        if (TypeUtils.isAssignableTo("java.util.Collection", t)) {
            return List.<String[]>of(new String[]{key(DomainValues.countKey(name(arg))), src(arg) + ".size()"});
        }
        Optional<String> reg = ctx.domain.keyOf(arg);
        if (reg.isPresent()) {
            return List.<String[]>of(new String[]{ctx.policy.logKey(reg.get()), src(arg)});
        }
        // the entity's id, read from the saved variable, the variable passed in, or the variable a call is made on
        // (save(invoice.markSent()) -> invoice.id()): never by calling the argument expression again
        String holder = savedVar != null ? savedVar : arg instanceof J.Identifier id ? id.getSimpleName()
                : arg instanceof J.MethodInvocation mi && mi.getSelect() instanceof J.Identifier sel
                && TypeUtils.isOfType(sel.getType(), t) ? sel.getSimpleName() : null;
        if (holder != null && TypeUtils.asFullyQualified(t) != null && ctx.a.index.clazz(TypeUtils.asFullyQualified(t).getFullyQualifiedName()) != null) {
            String[] id = idOf(t, holder);
            return id == null ? List.of() : List.<String[]>of(id);
        }
        if (t instanceof JavaType.Primitive || TypeUtils.isString(t) || TypeUtils.isAssignableTo("java.util.UUID", t)
                || TypeUtils.isAssignableTo("java.lang.Number", t)) {
            String d = DomainValues.derivedKey(arg);
            return d == null ? List.of() : List.<String[]>of(new String[]{key(d), src(arg)});
        }
        return List.of();
    }

    /** The statement (a direct child of a block) that contains {@code tree}. */
    static J enclosingStatement(MethodInfo m, J tree) {
        J[] found = {null};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Block visitBlock(J.Block b, Integer p) {
                for (Statement s : b.getStatements()) {
                    if (found[0] == null && Pos.start(s) <= Pos.start(tree) && Pos.end(tree) <= Pos.end(s)
                            && !(s instanceof J.Block) && !(s instanceof J.If) && !(s instanceof J.Try)
                            && !(s instanceof J.ForEachLoop) && !(s instanceof J.ForLoop) && !(s instanceof J.WhileLoop)) {
                        found[0] = s;
                    }
                }
                return super.visitBlock(b, p);
            }
        }.visit(m.decl().getBody(), 0);
        return found[0];
    }

    // ---- BATCH_SUMMARY_MISSING ------------------------------------------------------------------

    private void loops(MethodInfo m) {
        SourceFileInfo f = m.file();
        String text = f.text();
        Set<String> project = new HashSet<>();
        m.facts().calls.forEach(cs -> {
            if (cs.type() != null && ctx.a.index.clazz(cs.type().getDeclaringType().getFullyQualifiedName()) != null
                    && !cs.type().getName().matches("(get|is|has|to)[A-Z].*")) {
                project.add(cs.line() + ":" + cs.type().getName());
            }
        });
        List<J.ForEachLoop> found = new ArrayList<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda l, Integer p) {
                return l;
            }

            @Override
            public J.ForEachLoop visitForEachLoop(J.ForEachLoop l, Integer p) {
                found.add(l);
                return l;   // the outer loop is the batch; inner loops are its details
            }
        }.visit(m.decl().getBody(), 0);
        JavaEdits.LoggerPlan logger = ctx.logger(m.owner());
        int n = 0;
        for (J.ForEachLoop loop : found) {
            Expression it = loop.getControl().getIterable();
            if (!countable(it)) {
                continue;
            }
            int from = Pos.line(loop);
            int to = Pos.line(loop) + (int) loop.printTrimmed().lines().count();
            // work = I/O per item: a repository/HTTP/messaging call in the loop, or a project method that does one
            boolean work = m.facts().calls.stream().anyMatch(cs -> cs.line() >= from && cs.line() <= to && (ioCall(cs.type())
                    || cs.type() != null && project.contains(cs.line() + ":" + cs.type().getName())
                    && ctx.a.index.resolve(cs.type()).stream().anyMatch(t -> doesIo(t, 2))));
            if (!work) {
                continue;
            }
            int end = Pos.end(loop);
            boolean summarized = m.facts().logs.stream().anyMatch(l -> Pos.start(l.call()) > end && l.level().compareTo(Level.INFO) >= 0);
            count("loops", summarized);
            if (summarized || logger.name() == null || !ctx.enabled(Rules.BATCH_SUMMARY_MISSING) || ctx.ignored(f, from, Rules.BATCH_SUMMARY_MISSING)) {
                continue;
            }
            int[] lines = JavaEdits.statementLines(text, loop);
            OrderBuilder b = ctx.order(Rules.BATCH_SUMMARY_MISSING, m, from, "loop" + n++)
                    .title("Loop over '" + Text.oneLine(it.printTrimmed(), 40) + "' in " + m.label()
                            + " has no summary line: how many items were handled, and with what result?")
                    .context(ctx.context(m, lines[1], Text.oneLine(loop.printTrimmed(), 160), "MSG"));
            b.edits(logger.edits());
            String counted = counted(b, m, loop);
            List<String[]> values = List.<String[]>of(new String[]{key(countKeyFor(it, counted)), counted + ".size()"});
            b.edit(Edit.insert(f.relPath(), lines[1], Text.indentAt(text, Pos.start(loop)) + logLine(logger.name(), "info", "{{MSG}}", values) + "\n"));
            b.slot("MSG", slot("Summary after processing the items, e.g. 'Invoice export completed' (add counters such as failed "
                    + "items with \"args\")", values.stream().map(v -> v[0]).toList(), "completed"));
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** "CouponCleanupJob" → "Coupon cleanup"; a bare "Job"/"Task" class → its method name. */
    static String jobSubject(MethodInfo m) {
        String cls = m.owner().simpleName().replaceAll("(Job|Task|Scheduler|Runner|Listener|Handler|Executor|Worker|Service)$", "");
        String base = cls.isBlank() ? m.name() : cls;
        return FamilyRules.classWords(base);
    }

    private String phrase(String event, String subject) {
        String canonical = ctx.phrasing.canonicalOf(event);
        return canonical == null ? subject + " " + event : canonical.replace("{subject}", subject);
    }

    /** invoiceCount for a List<Invoice>, whatever the variable is called; else from the variable name. */
    private static String countKeyFor(Expression collection, String fallbackName) {
        if (collection.getType() instanceof JavaType.Parameterized p && p.getTypeParameters().size() == 1
                && TypeUtils.asFullyQualified(p.getTypeParameters().getFirst()) instanceof JavaType.FullyQualified el
                && !el.getFullyQualifiedName().startsWith("java.")) {
            return Text.lowerFirst(el.getClassName().replaceAll(".*\\.", "")) + "Count";
        }
        return DomainValues.countKey(fallbackName);
    }

    /** A collection held in a variable/field/getter (not a fixed List.of(..) or an enum's values()). */
    private static boolean countable(Expression it) {
        if (!TypeUtils.isAssignableTo("java.util.Collection", it.getType())) {
            return false;
        }
        Expression root = it instanceof J.MethodInvocation mi && mi.getSelect() != null ? mi.getSelect() : it;
        if (root instanceof J.Identifier id && id.getFieldType() != null
                && id.getFieldType().hasFlags(org.openrewrite.java.tree.Flag.Static, org.openrewrite.java.tree.Flag.Final)) {
            return false;   // a constant: its size is fixed
        }
        return it instanceof J.Identifier || it instanceof J.FieldAccess
                || it instanceof J.MethodInvocation mi && !Set.of("of", "values", "asList", "copyOf", "allOf").contains(mi.getSimpleName())
                && mi.getArguments().stream().allMatch(a -> a instanceof J.Empty);
    }

    private static String name(Expression e) {
        if (e instanceof J.Identifier id) {
            return id.getSimpleName();
        }
        if (e instanceof J.FieldAccess fa) {
            return fa.getSimpleName();
        }
        if (e instanceof J.MethodInvocation mi) {
            String n = mi.getSimpleName().replaceFirst("^(get|find|load|fetch)", "");
            return n.isEmpty() ? "item" : Text.lowerFirst(n);
        }
        return "item";
    }

    private String key(String raw) {
        return ctx.policy.logging().style().apply(raw);
    }

    private static String src(SourceFileInfo f, J tree) {
        return f.slice(Pos.start(tree), Pos.end(tree));
    }

    private static String src(J tree) {
        return tree.printTrimmed();
    }
}
