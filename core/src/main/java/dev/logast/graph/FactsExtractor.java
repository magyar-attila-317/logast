package dev.logast.graph;

import dev.logast.model.*;
import dev.logast.parse.Pos;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Collects logging, MDC, exception-handling, call and async facts from one method body. */
final class FactsExtractor extends JavaIsoVisitor<MethodFacts> {
    private static final Map<String, Level> LEVELS = Map.of(
            "trace", Level.TRACE, "debug", Level.DEBUG, "info", Level.INFO, "warn", Level.WARN, "error", Level.ERROR);
    private static final Map<String, Level> FLUENT = Map.of(
            "atTrace", Level.TRACE, "atDebug", Level.DEBUG, "atInfo", Level.INFO, "atWarn", Level.WARN, "atError", Level.ERROR);
    private static final Set<String> EXECUTOR_METHODS = Set.of(
            "execute", "submit", "invokeAll", "invokeAny", "schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay");

    private final String file;
    private final Deque<CatchInfo> catchStack = new ArrayDeque<>();
    private final Deque<List<CatchInfo>> guards = new ArrayDeque<>();
    private final Deque<Boolean> asyncStack = new ArrayDeque<>();
    private int finallyDepth;
    private int resourceDepth;
    private int loopDepth;
    /** Product of the bounds of the enclosing loops; -1 once any of them is unbounded. */
    private final Deque<Integer> loopBounds = new ArrayDeque<>();
    private J.ClassDeclaration owner;
    /** Per-element lambdas: collection forEach and stream pipeline stages (not Optional.map, not computeIfAbsent). */
    private static final Set<String> PER_ELEMENT = Set.of("forEach", "forEachOrdered", "map", "flatMap", "filter",
            "peek", "mapToObj", "mapToInt", "mapToLong", "anyMatch", "allMatch", "noneMatch");

    private FactsExtractor(String file) {
        this.file = file;
    }

    static MethodFacts extract(MethodInfo m) {
        MethodFacts facts = new MethodFacts();
        if (m.decl().getBody() != null) {
            Cursor parent = new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl());
            FactsExtractor fx = new FactsExtractor(m.file().relPath());
            fx.owner = m.owner().decl();
            fx.visit(m.decl().getBody(), facts, parent);
        }
        return facts;
    }

    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, MethodFacts f) {
        return c; // local classes are analysed as their own methods
    }

    @Override
    public J.NewClass visitNewClass(J.NewClass n, MethodFacts f) {
        if (n.getBody() == null && n.getArguments() != null && TypeUtils.isAssignableTo("java.lang.Thread", n.getType())
                && n.getArguments().stream().anyMatch(a -> !(a instanceof J.Empty))) {
            f.asyncHops.add(new AsyncHop(n, "new Thread", false, Pos.line(n)));
            asyncStack.push(false);
            J.NewClass r = super.visitNewClass(n, f);
            asyncStack.pop();
            return r;
        }
        if (n.getBody() != null) {
            if (n.getArguments() != null) {
                for (Expression a : n.getArguments()) {
                    visit(a, f, getCursor());
                }
            }
            return n;
        }
        return super.visitNewClass(n, f);
    }

    @Override
    public J.Try visitTry(J.Try t, MethodFacts f) {
        List<CatchInfo> infos = new ArrayList<>();
        for (J.Try.Catch c : t.getCatches()) {
            J.VariableDeclarations param = c.getParameter().getTree();
            String name = param.getVariables().getFirst().getSimpleName();
            JavaType type = param.getTypeExpression() == null ? null : param.getTypeExpression().getType();
            List<JavaType> types = type instanceof JavaType.MultiCatch mc
                    ? List.copyOf(mc.getThrowableTypes()) : type == null ? List.of() : List.of(type);
            infos.add(new CatchInfo(t, c, name, types));
        }
        f.catches.addAll(infos);
        if (t.getResources() != null) {
            resourceDepth++;
            for (J.Try.Resource r : t.getResources()) {
                visit(r, f, getCursor());
            }
            resourceDepth--;
        }
        guards.push(infos);
        visit(t.getBody(), f, getCursor());
        guards.pop();
        for (int i = 0; i < infos.size(); i++) {
            catchStack.push(infos.get(i));
            visit(t.getCatches().get(i), f, getCursor());
            catchStack.pop();
        }
        if (t.getFinally() != null) {
            finallyDepth++;
            visit(t.getFinally(), f, getCursor());
            finallyDepth--;
        }
        return t;
    }

    @Override
    public J.Lambda visitLambda(J.Lambda l, MethodFacts f) {
        Boolean managed = asyncParentManaged();
        if (managed != null) {
            asyncStack.push(managed);
        }
        boolean perElement = getCursor().getParentTreeCursor().getValue() instanceof J.MethodInvocation p
                && PER_ELEMENT.contains(p.getSimpleName()) && p.getMethodType() != null
                && (p.getMethodType().getDeclaringType().getFullyQualifiedName().startsWith("java.util.stream.")
                || TypeUtils.isAssignableTo("java.lang.Iterable", p.getMethodType().getDeclaringType())
                || TypeUtils.isAssignableTo("java.util.Map", p.getMethodType().getDeclaringType()));
        if (perElement) {
            loopDepth++;
            pushBound(bound(chainRoot(((J.MethodInvocation) getCursor().getParentTreeCursor().getValue()).getSelect())));
        }
        J.Lambda r = super.visitLambda(l, f);
        if (perElement) {
            loopDepth--;
            loopBounds.pop();
        }
        if (managed != null) {
            asyncStack.pop();
        }
        return r;
    }

    @Override
    public J.ForEachLoop visitForEachLoop(J.ForEachLoop loop, MethodFacts f) {
        loopDepth++;
        pushBound(bound(loop.getControl().getIterable()));
        J.ForEachLoop r = super.visitForEachLoop(loop, f);
        loopDepth--;
        loopBounds.pop();
        return r;
    }

    private void pushBound(int bound) {
        int outer = loopBounds.isEmpty() ? 1 : loopBounds.peek();
        loopBounds.push(outer < 0 || bound < 0 ? -1 : outer * bound);
    }

    private int currentBound() {
        return loopDepth == 0 || loopBounds.isEmpty() ? 0 : loopBounds.peek();
    }

    /** The collection a stream/forEach chain starts from: list.stream().filter(..) -> list. */
    private static Expression chainRoot(Expression e) {
        while (e instanceof J.MethodInvocation mi && mi.getSelect() != null
                && Set.of("stream", "parallelStream", "filter", "map", "sorted", "distinct", "limit", "peek", "flatMap",
                "mapToObj", "mapToInt", "boxed", "parallel", "sequential", "entrySet", "keySet", "values")
                .contains(mi.getSimpleName()) && !isEnumValues(mi)) {
            e = mi.getSelect();
        }
        return e;
    }

    private static boolean isEnumValues(J.MethodInvocation mi) {
        return mi.getSimpleName().equals("values") && mi.getArguments().stream().allMatch(a -> a instanceof J.Empty)
                && mi.getType() instanceof JavaType.Array arr && arr.getElemType() instanceof JavaType.Class c
                && c.getKind() == JavaType.FullyQualified.Kind.Enum;
    }

    /** Number of elements when it is fixed in the code (enum values, literal lists/arrays, same-class constants), else -1. */
    private int bound(Expression e) {
        if (e instanceof J.MethodInvocation mi) {
            if (isEnumValues(mi)) {
                return enumSize(((JavaType.Array) mi.getType()).getElemType());
            }
            String owner = mi.getMethodType() == null ? "" : mi.getMethodType().getDeclaringType().getFullyQualifiedName();
            List<Expression> args = args(mi);
            if (mi.getSimpleName().equals("allOf") && owner.equals("java.util.EnumSet") && args.size() == 1
                    && args.getFirst() instanceof J.FieldAccess fa && fa.getSimpleName().equals("class")) {
                return enumSize(fa.getTarget().getType());
            }
            if (mi.getSimpleName().equals("stream") && owner.equals("java.util.Arrays") && args.size() == 1) {
                return bound(args.getFirst());
            }
            boolean literalFactory = (mi.getSimpleName().equals("of") && (owner.equals("java.util.List") || owner.equals("java.util.Set")
                    || owner.equals("java.util.stream.Stream") || owner.equals("java.util.EnumSet") || owner.equals("java.util.Map")))
                    || (mi.getSimpleName().equals("asList") && owner.equals("java.util.Arrays"));
            if (literalFactory && !(args.size() == 1 && args.getFirst().getType() instanceof JavaType.Array)) {
                return owner.equals("java.util.Map") ? args.size() / 2 : args.size();
            }
            return -1;
        }
        if (e instanceof J.NewArray na && na.getInitializer() != null) {
            return (int) na.getInitializer().stream().filter(x -> !(x instanceof J.Empty)).count();
        }
        if (e instanceof J.Identifier id && id.getFieldType() != null
                && id.getFieldType().hasFlags(Flag.Static, Flag.Final) && owner != null) {
            for (Statement s : owner.getBody().getStatements()) {
                if (s instanceof J.VariableDeclarations vd) {
                    for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                        if (v.getSimpleName().equals(id.getSimpleName()) && v.getInitializer() != null) {
                            return bound(v.getInitializer());
                        }
                    }
                }
            }
        }
        return -1;
    }

    private static int enumSize(JavaType t) {
        if (t instanceof JavaType.FullyQualified fq && fq.getKind() == JavaType.FullyQualified.Kind.Enum) {
            long n = fq.getMembers().stream().filter(v -> v.hasFlags(Flag.Enum)).count();
            return n > 0 ? (int) n : -1;
        }
        return -1;
    }

    // for(;;)/while/do-while are left out on purpose: they are mostly retry and polling loops, where one INFO per
    // attempt is the signal, not noise.

    @Override
    public J.MemberReference visitMemberReference(J.MemberReference ref, MethodFacts f) {
        JavaType.Method mt = ref.getMethodType();
        if (mt != null && !"<constructor>".equals(mt.getName())) {
            Boolean managed = asyncParentManaged();
            boolean async = managed != null || !asyncStack.isEmpty();
            boolean asyncManaged = managed != null ? managed : !asyncStack.isEmpty() && asyncStack.peek();
            f.calls.add(new CallSite(ref, mt, Pos.line(ref), async, asyncManaged, guardSnapshot()));
        }
        return super.visitMemberReference(ref, f);
    }

    @Override
    public J.Throw visitThrow(J.Throw t, MethodFacts f) {
        CatchInfo c = catchStack.peek();
        if (c != null) {
            c.throwsList.add(t);
            if (references(t.getException(), c.param)) {
                c.rethrowsCause = true;
            }
        }
        return super.visitThrow(t, f);
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, MethodFacts f) {
        super.visitMethodInvocation(mi, f);
        JavaType.Method mt = mi.getMethodType();
        int line = Pos.line(mi);
        if (supportMdc(mi, f)) {
            return mi;
        }
        if (mt == null || mt.getDeclaringType() instanceof JavaType.Unknown) {
            f.unresolved.add(file + ":" + line + " " + mi.getSimpleName() + "(..)");
            return mi;
        }
        String owner = mt.getDeclaringType().getFullyQualifiedName();
        String name = mt.getName();
        CatchInfo inCatch = catchStack.peek();
        boolean statement = getCursor().getParentTreeCursor().getValue() instanceof J.Block;

        if (owner.equals("org.slf4j.Logger") && LEVELS.containsKey(name)) {
            List<Expression> args = args(mi);
            if (!args.isEmpty() && TypeUtils.isAssignableTo("org.slf4j.Marker", args.getFirst().getType())) {
                args = args.subList(1, args.size());
            }
            Expression template = args.isEmpty() ? null : args.getFirst();
            addLog(f, new LogCall(mi, LEVELS.get(name), false, template,
                    args.isEmpty() ? List.of() : List.copyOf(args.subList(1, args.size())), inCatch, statement, currentBound()));
            return mi;
        }
        if (owner.equals("org.slf4j.spi.LoggingEventBuilder") && name.equals("log")) {
            Level level = fluentLevel(mi);
            List<Expression> args = args(mi);
            addLog(f, new LogCall(mi, level == null ? Level.INFO : level, true,
                    args.isEmpty() ? null : args.getFirst(),
                    args.isEmpty() ? List.of() : List.copyOf(args.subList(1, args.size())), inCatch, statement, currentBound()));
            return mi;
        }
        if (owner.equals("org.slf4j.Logger") || owner.equals("org.slf4j.spi.LoggingEventBuilder")) {
            return mi; // isDebugEnabled, atInfo(), addKeyValue(), ...
        }
        if (owner.equals("org.slf4j.MDC")) {
            MdcOp.Kind kind = switch (name) {
                case "put" -> MdcOp.Kind.PUT;
                case "putCloseable" -> MdcOp.Kind.PUT_CLOSEABLE;
                case "remove" -> MdcOp.Kind.REMOVE;
                case "clear" -> MdcOp.Kind.CLEAR;
                case "setContextMap" -> MdcOp.Kind.SET_CONTEXT_MAP;
                default -> MdcOp.Kind.OTHER;
            };
            List<Expression> args = args(mi);
            String key = !args.isEmpty() && args.getFirst() instanceof J.Literal l && l.getValue() instanceof String s ? s : null;
            Object parent = getCursor().getParentTreeCursor().getValue();
            f.mdc.add(new MdcOp(kind, key, mi, parent instanceof J.Block b ? b : null, finallyDepth > 0, resourceDepth > 0));
            return mi;
        }
        if (inCatch != null && name.equals("interrupt") && owner.equals("java.lang.Thread")) {
            inCatch.restoresInterrupt = true;
        }
        if (inCatch != null && args(mi).stream().anyMatch(a -> a instanceof J.Identifier id && id.getSimpleName().equals(inCatch.param))) {
            inCatch.passesException = true;
        }
        Boolean managed = asyncApiManaged(mi);
        if (managed != null) {
            String api = owner.startsWith("java.lang.Thread") ? "Thread." + name : Types.simple(owner) + "." + name;
            f.asyncHops.add(new AsyncHop(mi, api, managed, line));
            return mi;
        }
        boolean async = !asyncStack.isEmpty();
        f.calls.add(new CallSite(mi, mt, line, async, async && asyncStack.peek(), guardSnapshot()));
        return mi;
    }

    /**
     * logging-support API ({@code MdcScope.of/and}, {@code ObservedContext...mdc}) sets MDC for a try-with-resources
     * scope. Matched by name as well as type: right after the starter is added the classpath file is often stale.
     */
    private boolean supportMdc(J.MethodInvocation mi, MethodFacts f) {
        String name = mi.getSimpleName();
        if (!name.equals("of") && !name.equals("and") && !name.equals("mdc")) {
            return false;
        }
        Expression root = mi.getSelect();
        while (root instanceof J.MethodInvocation inner) {
            root = inner.getSelect();
        }
        String owner = root instanceof J.Identifier id ? id.getSimpleName()
                : root instanceof J.FieldAccess fa ? fa.getSimpleName() : null;
        if (!"MdcScope".equals(owner) && !"ObservedContext".equals(owner)) {
            return false;
        }
        if (name.equals("of") && !"MdcScope".equals(owner)) {
            return false;
        }
        List<Expression> args = args(mi);
        // MdcScope.of(k1, v1, k2, v2, ...): one op per literal key
        for (int i = 0; i < args.size(); i += 2) {
            String key = args.get(i) instanceof J.Literal l && l.getValue() instanceof String s ? s : null;
            f.mdc.add(new MdcOp(MdcOp.Kind.PUT_CLOSEABLE, key, mi, null, finallyDepth > 0, true));
            if (!name.equals("of")) {
                break;
            }
        }
        return true;
    }

    private void addLog(MethodFacts f, LogCall call) {
        f.logs.add(call);
        if (call.inCatch() != null) {
            call.inCatch().logs.add(call);
        }
    }

    private List<CatchInfo> guardSnapshot() {
        List<CatchInfo> out = new ArrayList<>();
        guards.forEach(out::addAll);
        return List.copyOf(out);
    }

    /** If the parent tree is an async API call, whether it is Spring-managed; null when not async. */
    private Boolean asyncParentManaged() {
        Object parent = getCursor().getParentTreeCursor().getValue();
        return parent instanceof J.MethodInvocation mi ? asyncApiManaged(mi) : null;
    }

    /** null when {@code mi} does not hand work to another thread; otherwise whether a Spring TaskDecorator applies. */
    static Boolean asyncApiManaged(J.MethodInvocation mi) {
        JavaType.Method mt = mi.getMethodType();
        if (mt == null) {
            return null;
        }
        String owner = mt.getDeclaringType().getFullyQualifiedName();
        String name = mt.getName();
        if (owner.equals("java.util.concurrent.CompletableFuture") && name.endsWith("Async")) {
            List<Expression> args = args(mi);
            return !args.isEmpty() && isSpringExecutor(args.getLast().getType());
        }
        // Thread.startVirtualThread(r), Thread.ofVirtual().start(r), Thread.ofPlatform().unstarted(r)
        if (owner.startsWith("java.lang.Thread") && Set.of("startVirtualThread", "start", "unstarted").contains(name)
                && !args(mi).isEmpty()) {
            return false;
        }
        // lambdas of a parallel stream run in the ForkJoin common pool
        if (owner.startsWith("java.util.stream.") && parallelChain(mi)
                && args(mi).stream().anyMatch(a -> a instanceof J.Lambda || a instanceof J.MemberReference)) {
            return false;
        }
        if (EXECUTOR_METHODS.contains(name) && (TypeUtils.isAssignableTo("java.util.concurrent.Executor", mt.getDeclaringType())
                || TypeUtils.isAssignableTo("org.springframework.scheduling.TaskScheduler", mt.getDeclaringType()))) {
            JavaType receiver = mi.getSelect() == null ? null : mi.getSelect().getType();
            return isSpringExecutor(receiver);
        }
        return null;
    }

    private static boolean parallelChain(J.MethodInvocation mi) {
        Expression sel = mi.getSelect();
        while (sel instanceof J.MethodInvocation s) {
            if (s.getSimpleName().equals("parallelStream") || s.getSimpleName().equals("parallel")) {
                return true;
            }
            sel = s.getSelect();
        }
        return false;
    }

    private static boolean isSpringExecutor(JavaType t) {
        return TypeUtils.isAssignableTo("org.springframework.core.task.TaskExecutor", t)
                || TypeUtils.isAssignableTo("org.springframework.scheduling.TaskScheduler", t);
    }

    private static Level fluentLevel(J.MethodInvocation mi) {
        Expression sel = mi.getSelect();
        while (sel instanceof J.MethodInvocation s) {
            Level l = FLUENT.get(s.getSimpleName());
            if (l != null) {
                return l;
            }
            sel = s.getSelect();
        }
        return null;
    }

    static List<Expression> args(J.MethodInvocation mi) {
        return mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
    }

    static boolean references(J tree, String name) {
        AtomicBoolean found = new AtomicBoolean();
        new JavaIsoVisitor<AtomicBoolean>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier id, AtomicBoolean p) {
                if (id.getSimpleName().equals(name)) {
                    p.set(true);
                }
                return id;
            }
        }.visit(tree, found);
        return found.get();
    }
}
