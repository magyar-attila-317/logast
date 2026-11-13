package dev.logast.rules;

import dev.logast.model.LogCall;
import dev.logast.policy.Policy;
import dev.logast.model.MdcOp;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.*;
import java.util.regex.Pattern;

/**
 * REPEATED_LOG_VALUE: a vocabulary identifier logged by several statements of one method, and not in MDC there,
 * goes into MDC once ({@code try (var mdc = MdcScope.of(..)) { .. }}); the message rules then drop it from each
 * message (they depend on this order, so the value is never lost in between).
 * <p>Counted along the call chain: log lines in the method and in the methods it passes the same value to. The
 * scope goes into the highest method that has the value and reaches the threshold, unless every caller already covers
 * it; the lines below then drop the value from their messages (it is in MDC on every path to them).
 * <p>The scope starts where the value exists: the whole body for a parameter, the statements after the declaration
 * for a local. Values must be side-effect free (a variable or a getter chain on one) and never reassigned; a getter
 * on a variable that the code null-checks is skipped, since evaluating it earlier could throw.
 */
public final class RepeatedValueRules {
    private final RuleContext ctx;

    RepeatedValueRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    private record Candidate(String key, String expr, String root, List<LogCall> logs) {
    }

    private record Region(int start, int end, String indent, boolean wholeBody) {
    }

    /** Why a key was or was not put into MDC in a method: shown by `logast why <key>`. */
    public record Decision(String key, String method, String location, String outcome, String reason) {
    }

    private void decide(String key, MethodInfo m, String outcome, String reason) {
        ctx.mdcDecisions.add(new Decision(key, m.label(), m.file().relPath() + ":" + m.line(), outcome, reason));
    }

    void run() {
        int min = ctx.policy.mdc().extractRepeated();
        if (!ctx.enabled(Rules.REPEATED_LOG_VALUE) || min <= 0) {
            return;
        }
        // phase 1: what every method would scope, under exactly the conditions that produce an order
        Map<String, Map<String, Plan>> plans = new LinkedHashMap<>();
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() != null && !ctx.wrappedMethods.contains(m.key())
                    && !ctx.legacyMdc(m)) {   // MDC_NOT_CLOSEABLE / MDC_CLEAR come first
                Map<String, Plan> p = plans(m, min);
                if (!p.isEmpty()) {
                    plans.put(m.key(), p);
                }
            }
        }
        lift(plans);
        // phase 2: a method leaves a key to its callers only when every caller really scopes it (or has it in MDC)
        for (var e : plans.entrySet()) {
            MethodInfo m = ctx.a.index.method(e.getKey());
            Map<String, Plan> mine = new LinkedHashMap<>(e.getValue());
            mine.keySet().removeIf(key -> {
                boolean covered = coveredByCallers(m, key, plans, 0, new HashSet<>());
                if (covered) {
                    decide(key, m, "covered", "every caller has it in MDC or scopes it higher up");
                }
                return covered;
            });
            mine.forEach((key, p) -> {
                List<String> open = uncovered(m, key, plans).stream().map(MethodInfo::label).toList();
                decide(key, m, "scope", "logged by " + p.total() + " lines here and below"
                        + (ctx.a.entrypoints.stream().anyMatch(ep -> ep.method().key().equals(m.key())) ? "; the start of the flow (an entrypoint)"
                        : ctx.a.graph.in(m).isEmpty() ? "; nothing in the code calls this method"
                        : open.isEmpty() ? "" : "; needed for the path(s) through " + String.join(", ", open)));
            });
            emit(m, mine);
        }
    }

    /** Callers of {@code m} through which {@code key} does not arrive in MDC (in MDC, scoped, or covered higher up). */
    private List<MethodInfo> uncovered(MethodInfo m, String key, Map<String, Map<String, Plan>> plans) {
        List<MethodInfo> out = new ArrayList<>();
        for (var e : ctx.a.graph.in(m)) {
            MethodInfo c = e.from();
            Map<String, Plan> theirs = plans.get(c.key());
            if (!c.key().equals(m.key()) && !ctx.mdcInside(c).contains(key) && (theirs == null || !theirs.containsKey(key))
                    && !coveredByCallers(c, key, plans, 0, new HashSet<>()) && !out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * When only some callers of a method bring the key, a scope in the method would repeat it on the covered paths.
     * The uncovered callers get the scope instead, when they have the value as a stable variable; the method below
     * then has the key on every path. If any of them cannot (a literal, a computed value), the scope stays below.
     */
    private void lift(Map<String, Map<String, Plan>> plans) {
        for (int round = 0; round < CHAIN_DEPTH; round++) {
            boolean changed = false;
            for (var entry : new ArrayList<>(plans.entrySet())) {
                MethodInfo m = ctx.a.index.method(entry.getKey());
                for (var keyPlan : new ArrayList<>(entry.getValue().entrySet())) {
                    String key = keyPlan.getKey();
                    List<MethodInfo> open = uncovered(m, key, plans);
                    int callers = (int) ctx.a.graph.in(m).stream().map(e -> e.from().key()).distinct().count();
                    if (open.isEmpty() || open.size() == callers) {
                        continue;   // covered everywhere (dropped later), or nowhere: the method itself is the place
                    }
                    Map<String, Plan> lifted = new LinkedHashMap<>();
                    for (MethodInfo c : open) {
                        Plan p = liftTo(c, m, key, keyPlan.getValue().total());
                        if (p == null) {
                            lifted = null;
                            break;
                        }
                        lifted.put(c.key(), p);
                    }
                    if (lifted != null) {
                        lifted.forEach((ck, p) -> {
                            plans.computeIfAbsent(ck, x -> new LinkedHashMap<>()).put(key, p);
                            decide(key, ctx.a.index.method(ck), "lifted", "scoped here instead of in " + m.label()
                                    + ", which other paths reach with " + key + " in MDC already");
                        });
                        changed = true;
                    }
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    /** A plan for caller {@code c} that scopes the value it hands to {@code m}; null when it has no stable variable for it. */
    private Plan liftTo(MethodInfo c, MethodInfo m, String key, int total) {
        if (c.decl().getBody() == null || ctx.wrappedMethods.contains(c.key()) || ctx.legacyMdc(c)
                || c.owner().fqn().startsWith(ctx.a.supportPackage + ".")) {
            return null;
        }
        Expression passed = passes(c, key).stream().filter(p -> p.getKey().to().key().equals(m.key()))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        String root = passed == null ? null : root(passed);
        String expr = passed == null ? null : handedOn(passed, key);
        if (root == null || expr == null || setBelow(c, key, 0, new HashSet<>())) {
            return null;
        }
        Candidate cand = new Candidate(key, expr, root, new ArrayList<>());
        Region r = region(c, c.file().text(), cand);
        return r == null ? null : new Plan(cand, r, total);
    }

    private record Plan(Candidate candidate, Region region, int total) {
    }

    /**
     * On every path into {@code m}, a method above has {@code key} in MDC or gets a scope for it in this run (not only
     * the direct callers: listener → method (scope) → helper → helper (logs it) is covered too).
     */
    private boolean coveredByCallers(MethodInfo m, String key, Map<String, Map<String, Plan>> plans, int depth, Set<String> seen) {
        var in = ctx.a.graph.in(m);
        if (in.isEmpty() || depth > CHAIN_DEPTH || !seen.add(m.key())) {
            return false;
        }
        for (var e : in) {
            MethodInfo caller = e.from();
            if (caller.key().equals(m.key())) {
                return false;   // recursion: scope here
            }
            if (ctx.mdcInside(caller).contains(key)) {
                continue;
            }
            Map<String, Plan> theirs = plans.get(caller.key());
            if (theirs != null && theirs.containsKey(key)) {
                continue;   // the caller scopes it: a scope here would nest
            }
            if (!coveredByCallers(caller, key, plans, depth + 1, new HashSet<>(seen))) {
                return false;
            }
        }
        return true;
    }

    private static final int CHAIN_DEPTH = 4;

    /** The MDC key a log argument or call argument carries: a vocabulary source, or the [key={}] name it is logged under. */
    private String keyOf(Expression e, String given) {
        Map<String, String> aliases = ctx.policy.mdcAliases();
        Set<String> registry = ctx.policy.mdcRegistry().keySet();
        return ctx.domain.keyOf(e).orElse(given == null ? null : registry.contains(given) ? given : aliases.get(given));
    }

    /**
     * The value a call argument hands on for {@code key}: the argument itself when it carries the key, or the
     * vocabulary accessor on an object that holds it ({@code order} → {@code order.id()}); null otherwise.
     */
    private String handedOn(Expression a, String key) {
        if (key.equals(keyOf(a, null)) || a instanceof J.Identifier id && key.equals(keyOf(a, id.getSimpleName()))) {
            return a.printTrimmed().replaceAll("\\s+", "");
        }
        if (a instanceof J.Identifier id && a.getType() != null) {
            Policy.KeySource src = ctx.policy.mdcRegistry().get(key);
            String accessor = src == null ? null : src.types().get(dev.logast.graph.Types.erasure(a.getType()));
            if (accessor != null) {
                return id.getSimpleName() + "." + accessor;
            }
            String found = src == null ? null : accessorFor(a.getType(), src.params());
            if (found != null) {
                return id.getSimpleName() + "." + found;   // salesData → salesData.couponId(): the object carries it
            }
        }
        return null;
    }

    /**
     * An accessor of {@code type} named like a source parameter of the key: {@code couponId()} or {@code getCouponId()},
     * or a record component / field {@code couponId}. Records and DTOs carry their ids this way without a mapping.
     */
    static String accessorFor(org.openrewrite.java.tree.JavaType type, List<String> names) {
        if (!(org.openrewrite.java.tree.TypeUtils.asFullyQualified(type) instanceof org.openrewrite.java.tree.JavaType.FullyQualified fq)) {
            return null;
        }
        for (String n : names) {
            String getter = "get" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
            for (var mt : fq.getMethods()) {
                if (mt.getParameterTypes().isEmpty() && (mt.getName().equals(n) || mt.getName().equals(getter))) {
                    return mt.getName() + "()";
                }
            }
            for (var v : fq.getMembers()) {
                if (v.getName().equals(n)) {
                    return fq.getKind() == org.openrewrite.java.tree.JavaType.FullyQualified.Kind.Record ? n + "()" : null;
                }
            }
        }
        return null;
    }

    /** Log arguments carrying {@code key} in {@code m}. */
    private int ownCount(MethodInfo m, String key) {
        int n = 0;
        for (LogCall lc : m.facts().logs) {
            if (lc.offThread()) {
                continue;
            }
            List<String> named = lc.template() instanceof J.Literal l && l.getValue() instanceof String s
                    ? MessageTemplate.placeholderNames(s) : List.of();
            for (int i = 0; i < lc.args().size(); i++) {
                if (key.equals(keyOf(lc.args().get(i), i < named.size() ? named.get(i) : null))) {
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Calls in {@code m} that pass {@code key}'s value on, with the argument: an argument carrying the key, or one
     * that goes into a parameter of the callee that is a source of the key ({@code method1(request.code())} into
     * {@code method1(String couponId)}).
     */
    private List<Map.Entry<dev.logast.graph.CallGraph.Edge, Expression>> passes(MethodInfo m, String key) {
        List<Map.Entry<dev.logast.graph.CallGraph.Edge, Expression>> out = new ArrayList<>();
        Policy.KeySource src = ctx.policy.mdcRegistry().get(key);
        for (var e : ctx.a.graph.out(m)) {
            if (e.site() != null && e.site().tree() instanceof J.MethodInvocation mi) {
                List<Expression> args = mi.getArguments();
                List<String> params = paramNames(e.to());
                for (int i = 0; i < args.size(); i++) {
                    Expression a = args.get(i);
                    boolean intoKeyParam = src != null && i < params.size() && src.params().contains(params.get(i))
                            && !(a instanceof J.Literal) && root(a) != null;
                    if (handedOn(a, key) != null || intoKeyParam) {
                        out.add(Map.entry(e, a));
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Parameters and locals of the method itself (not of its lambdas or anonymous classes). */
    private static Set<String> methodLevelNames(MethodInfo m) {
        Set<String> out = new HashSet<>(paramNames(m));
        if (m.decl().getBody() == null) {
            return out;
        }
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda l, Integer p) {
                return l;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass n, Integer p) {
                return n.getBody() == null ? super.visitNewClass(n, p) : n;
            }

            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, Integer p) {
                out.add(v.getSimpleName());
                return super.visitVariable(v, p);
            }
        }.visit(m.decl().getBody(), 0);
        return out;
    }

    private static List<String> paramNames(MethodInfo m) {
        List<String> out = new ArrayList<>();
        for (var p : m.decl().getParameters()) {
            if (p instanceof J.VariableDeclarations vd) {
                vd.getVariables().forEach(v -> out.add(v.getSimpleName()));
            }
        }
        return out;
    }

    /** A method below already puts {@code key} into MDC itself (passed the value or not): a scope here would nest. */
    private boolean setBelow(MethodInfo m, String key, int depth, Set<String> seen) {
        if (depth > CHAIN_DEPTH) {
            return false;
        }
        for (var e : ctx.a.graph.out(m)) {
            MethodInfo callee = e.to();
            if (!seen.add(callee.key())) {
                continue;
            }
            // an old-style MDC.put below blocks a scope here; a scope below does not: once this one is in, the one below
            // has the key on entry and MDC_NESTED_KEY unwraps it (the scope moves up)
            if (callee.facts().mdc.stream().anyMatch(op -> op.kind() != MdcOp.Kind.PUT_CLOSEABLE
                    && (key.equals(op.key()) || key.equals(ctx.policy.mdcAliases().get(op.key()))))
                    || setBelow(callee, key, depth + 1, seen)) {
                return true;
            }
        }
        return false;
    }

    /** Log lines with {@code key} below {@code m}, following calls that pass the value on. */
    private int chainCount(MethodInfo m, String key, int depth, Set<String> seen) {
        if (depth > CHAIN_DEPTH) {
            return 0;
        }
        int n = 0;
        for (var p : passes(m, key)) {
            MethodInfo callee = p.getKey().to();
            if (seen.add(callee.key())) {
                n += ownCount(callee, key) + chainCount(callee, key, depth + 1, seen);
            }
        }
        return n;
    }

    private Map<String, Plan> plans(MethodInfo m, int min) {
        SourceFileInfo f = m.file();
        String text = f.text();
        Set<String> inMdc = ctx.mdcInside(m);
        Map<String, String> aliases = ctx.policy.mdcAliases();
        Set<String> registry = ctx.policy.mdcRegistry().keySet();

        Map<String, Candidate> byKey = new LinkedHashMap<>();
        Set<String> ambiguous = new HashSet<>();
        Set<String> ownParams = new HashSet<>(paramNames(m));
        Set<String> methodLevel = methodLevelNames(m);
        for (LogCall lc : m.facts().logs) {
            if (lc.offThread()) {
                continue;   // runs on another thread: a scope here does not reach it
            }
            List<String> named = lc.template() instanceof J.Literal l && l.getValue() instanceof String s
                    ? MessageTemplate.placeholderNames(s) : List.of();
            for (int i = 0; i < lc.args().size(); i++) {
                Expression e = lc.args().get(i);
                String given = i < named.size() ? named.get(i) : null;
                String key = ctx.domain.keyOf(e).orElse(given == null ? null
                        : registry.contains(given) ? given : aliases.get(given));
                if (key == null || ambiguous.contains(key)) {
                    continue;
                }
                if (inMdc.contains(key)) {
                    decide(key, m, "in MDC", "already in MDC here: the message rules drop it from the lines");
                    continue;
                }
                String root = root(e);
                String expr = e.printTrimmed().replaceAll("\\s+", "");
                if (root == null || !methodLevel.contains(root)) {
                    continue;   // a computed value or a lambda's own parameter: it does not decide where the key goes
                }
                Candidate c = byKey.get(key);
                if (c != null && !c.expr().equals(expr)) {
                    // one key, one value per flow: keep the value that exists for the whole method (a parameter)
                    if (ownParams.contains(root) && !ownParams.contains(c.root())) {
                        byKey.put(key, c = new Candidate(key, expr, root, new ArrayList<>(c.logs())));
                    }
                }
                if (c == null) {
                    byKey.put(key, c = new Candidate(key, expr, root, new ArrayList<>()));
                }
                if (!c.logs().contains(lc)) {
                    c.logs().add(lc);
                }
            }
        }

        // values this method does not log itself but passes on to methods that do
        for (var e : ctx.a.graph.out(m)) {
            if (e.site() != null && e.site().tree() instanceof J.MethodInvocation mi) {
                List<String> params = paramNames(e.to());
                for (int i = 0; i < mi.getArguments().size(); i++) {
                    Expression a = mi.getArguments().get(i);
                    String param = i < params.size() ? params.get(i) : null;
                    for (String key : ctx.policy.mdcRegistry().keySet()) {
                        String expr = handedOn(a, key);
                        if (expr == null && param != null && ctx.policy.mdcRegistry().get(key).params().contains(param)
                                && !(a instanceof J.Literal)) {
                            expr = a.printTrimmed().replaceAll("\\s+", "");   // goes into a couponId parameter
                        }
                        String root = expr == null ? null : root(a);
                        if (root != null && !inMdc.contains(key) && !ambiguous.contains(key) && !byKey.containsKey(key)) {
                            byKey.put(key, new Candidate(key, expr, root, new ArrayList<>()));
                        }
                    }
                }
            }
        }

        Map<String, Plan> out = new LinkedHashMap<>();
        for (Candidate c : byKey.values()) {
            int total = c.logs().size() + chainCount(m, c.key(), 0, new HashSet<>(Set.of(m.key())));
            if (setBelow(m, c.key(), 0, new HashSet<>())) {
                decide(c.key(), m, "no scope", "a method below sets " + c.key() + " in MDC itself: a scope here would nest");
                continue;
            }
            Region r = region(m, text, c);
            if (total < min) {
                if (!c.logs().isEmpty()) {
                    decide(c.key(), m, "no scope", "logged by " + total + " line(s) here and below; the threshold is " + min);
                }
                continue;
            }
            if (r != null) {
                out.put(c.key(), new Plan(c, r, total));
            } else {
                decide(c.key(), m, "no scope", "'" + c.root() + "' is reassigned, null-checked or a loop variable: not stable");
            }
        }
        return out;
    }

    private void emit(MethodInfo m, Map<String, Plan> plans) {
        if (plans.isEmpty()) {
            return;
        }
        Map<Region, List<Candidate>> byRegion = new LinkedHashMap<>();
        Map<String, Integer> totals = new HashMap<>();
        plans.forEach((key, p) -> {
            totals.put(key, p.total());
            byRegion.computeIfAbsent(p.region(), x -> new ArrayList<>()).add(p.candidate());
        });
        SourceFileInfo f = m.file();
        String text = f.text();
        // one wrap per method and round; overlapping regions come back after the rescan
        byRegion.entrySet().stream()
                .max(Comparator.comparingInt((Map.Entry<Region, List<Candidate>> e) -> e.getValue().size())
                        .thenComparingInt(e -> -(e.getKey().end() - e.getKey().start())))
                .ifPresent(e -> order(m, f, text, e.getKey(), e.getValue(), totals));
    }

    private void order(MethodInfo m, SourceFileInfo f, String text, Region r, List<Candidate> cs, Map<String, Integer> totals) {
        int firstLine = cs.stream().flatMap(c -> c.logs().stream()).mapToInt(lc -> Pos.line(lc.call())).min().orElse(m.line());
        int own = (int) cs.stream().flatMap(c -> c.logs().stream()).distinct().count();
        int total = cs.stream().mapToInt(c -> totals.getOrDefault(c.key(), 0)).max().orElse(own);
        List<String> keys = cs.stream().map(Candidate::key).toList();
        OrderBuilder b = ctx.order(Rules.REPEATED_LOG_VALUE, m, firstLine, "repeat|" + String.join(",", keys))
                .title(String.join(", ", keys) + " logged by " + total + " statements in " + m.label()
                        + (total > own ? " and the methods it passes " + (keys.size() == 1 ? "it" : "them") + " to" : "")
                        + ": put " + (keys.size() == 1 ? "it" : "them") + " into MDC once, here, instead of into every message");
        String unit = Text.indentUnit(text);
        String var = EntrypointRules.unique("mdc", ctx.taken(m, r.start()));
        String body = text.substring(r.start(), r.end());
        String scope = EntrypointRules.mdcScope(cs.stream().map(c -> new DomainValues.Value(c.key(), c.expr())).toList());
        b.edit(Edit.replace(f.relPath(), r.start(), r.end(),
                r.indent() + "try (var " + var + " = " + scope + ") {\n" + Text.reindent(body, unit) + r.indent() + "}\n"));
        b.edits(JavaEdits.imports(f, ctx.support("MdcScope")));
        ctx.dependOnStarter(b);
        b.context(ctx.context(m, r.start(), Text.oneLine(body, 200), null));
        String id = b.id();
        for (String k : keys) {
            (r.wholeBody() ? ctx.plannedOwnKeys : ctx.plannedLocalKeys).computeIfAbsent(m.key(), x -> new TreeSet<>()).add(k);
            ctx.plannedBy.put(m.key() + "|" + k, id);
        }
    }

    /** Where the value exists and stays the same: whole body (parameter) or after its declaration (local). */
    private Region region(MethodInfo m, String text, Candidate c) {
        J.Block body = m.decl().getBody();
        boolean param = m.decl().getParameters().stream().anyMatch(p -> p instanceof J.VariableDeclarations vd
                && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(c.root())));
        Region r = null;
        if (param && !m.decl().isConstructor()) {
            int[] inner = JavaEdits.blockInner(text, body);
            if (inner != null) {
                r = new Region(inner[0], inner[1], Text.indentAt(text, Pos.start(body)) + Text.indentUnit(text), true);
            }
        } else if (!param) {
            r = afterDeclaration(text, body, c.root());   // a loop variable changes every pass: never scoped
        }
        if (r == null) {
            return null;
        }
        int rs = r.start(), re = r.end();
        if (c.logs().stream().anyMatch(lc -> Pos.start(lc.call()) < rs || Pos.end(lc.call()) > re)) {
            return null;
        }
        // string literals and comments out: "[couponId={}]" in a message is not an assignment
        String region = text.substring(rs, re).replaceAll("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|//[^\n]*|/\\*[\\s\\S]*?\\*/", "\"\"");
        String v = Pattern.quote(c.root());
        if (Pattern.compile("(?<![\\w.])" + v + "\\s*(?:[-+*/%&|^]?=(?!=)|\\+\\+|--)|(?:\\+\\+|--)" + v + "\\b").matcher(region).find()) {
            return null;    // reassigned: the MDC value would go stale
        }
        if (!c.expr().equals(c.root()) && Pattern.compile("(?<![\\w.])" + v + "\\s*[!=]=\\s*null|null\\s*[!=]=\\s*" + v
                + "\\b|Objects\\.(?:isNull|nonNull)\\(\\s*" + v + "\\s*\\)").matcher(region).find()) {
            return null;    // the code null-checks it: evaluating the getter up front could throw
        }
        return r;
    }

    private static Region afterDeclaration(String text, J.Block block, String name) {
        List<Statement> stmts = block.getStatements();
        for (int i = 0; i < stmts.size(); i++) {
            Statement s = stmts.get(i);
            if (s instanceof J.VariableDeclarations vd && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(name))) {
                int[] inner = JavaEdits.blockInner(text, block);
                int semi = text.indexOf(';', Pos.end(s) - 1);
                if (inner == null || semi < 0 || i == stmts.size() - 1
                        || !Text.blankOrComment(text, semi + 1, Text.lineEnd(text, semi))) {
                    return null;
                }
                int start = Text.nextLine(text, semi);
                return start < inner[1] ? new Region(start, inner[1], Text.indentAt(text, Pos.start(s)), false) : null;
            }
            for (J.Block nested : nestedBlocks(s)) {
                Region r = afterDeclaration(text, nested, name);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private static List<J.Block> nestedBlocks(Statement s) {
        List<J.Block> out = new ArrayList<>();
        if (s instanceof J.Block b) {
            out.add(b);
        } else if (s instanceof J.If i) {
            if (i.getThenPart() instanceof J.Block b) {
                out.add(b);
            }
            if (i.getElsePart() != null && i.getElsePart().getBody() instanceof J.Block b) {
                out.add(b);
            }
        } else if (s instanceof J.Try t) {
            out.add(t.getBody());
            t.getCatches().forEach(c -> out.add(c.getBody()));
        } else if (s instanceof J.ForEachLoop l && l.getBody() instanceof J.Block b) {
            out.add(b);
        } else if (s instanceof J.ForLoop l && l.getBody() instanceof J.Block b) {
            out.add(b);
        } else if (s instanceof J.WhileLoop l && l.getBody() instanceof J.Block b) {
            out.add(b);
        } else if (s instanceof J.Synchronized sy) {
            out.add(sy.getBody());
        }
        return out;
    }

    /** Variable a side-effect-free value expression reads: {@code orderId}, {@code order.getId()}, {@code cmd.order().id()}. */
    static String root(Expression e) {
        if (e instanceof J.Identifier id) {
            return id.getSimpleName();
        }
        if (e instanceof J.FieldAccess fa) {
            return fa.getTarget() instanceof Expression t ? root(t) : null;
        }
        if (e instanceof J.MethodInvocation mi && mi.getSelect() != null && DomainValues.FactsArgs.noArgs(mi)
                && (mi.getSimpleName().startsWith("get") || mi.getSimpleName().startsWith("is")
                || !mi.getSimpleName().matches("(?:create|new|next|generate|random|build|compute|fetch|load|find|save|remove|poll|take).*"))) {
            return root(mi.getSelect());
        }
        return null;
    }
}
