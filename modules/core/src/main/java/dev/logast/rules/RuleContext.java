package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.graph.PathContext;
import dev.logast.graph.ScopeResolver;
import dev.logast.graph.Types;
import dev.logast.model.CatchInfo;
import dev.logast.model.MethodInfo;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.SourceFileInfo;
import dev.logast.policy.Policy;
import org.openrewrite.java.tree.JavaType;

import java.util.*;

/** Shared state while rules run: the analysis, the "after planned fixes" path context, created orders. */
public final class RuleContext {
    public final Analysis a;
    public final Policy policy;
    public final DomainValues domain;
    public final Glossary glossary;
    public final Phrasing phrasing;
    final List<OrderBuilder> orders = new ArrayList<>();
    final Map<CatchInfo, String> swallowedOrders = new IdentityHashMap<>();
    /** What the entry phase plans (Rules.scan freezes it when the phase ends). */
    final Plans.Entry entry = new Plans.Entry();
    /** What the MDC phase plans (frozen after it). */
    final Plans.Mdc mdc = new Plans.Mdc();
    private LifecyclePlan lifecycle;
    /** File → rules its {@code logast:ignore-file} comments silence. */
    private final Map<String, Set<String>> fileIgnores = new HashMap<>();

    /**
     * #272: the work a startup one-shot starts: the first command (a void method) of another class of the service it
     * calls directly ({@code job.execute()}), or null when it only does local or setup work (its own helpers, getters).
     */
    public MethodInfo startedWork(MethodInfo m) {
        for (var e : a.graph.out(m)) {
            MethodInfo to = e.to();
            if (!to.owner().fqn().equals(m.owner().fqn()) && !to.owner().fqn().startsWith(a.supportPackage + ".")
                    && to.type().getReturnType() == JavaType.Primitive.Void) {
                return to;
            }
        }
        return null;
    }

    /**
     * #272: a startup one-shot ({@link dev.logast.graph.EntrypointFinder#oneShot}) that starts no work of the service: no
     * observation, catalog constant or counter of its own. One that starts work is observed, named after that work: an
     * application whose only entrypoint is a ready listener is still traced and timed.
     */
    public boolean idleOneShot(dev.logast.model.Entrypoint ep) {
        return dev.logast.graph.EntrypointFinder.oneShot(ep) && startedWork(ep.method()) == null;
    }

    /** Who owns which lifecycle line of every flow (built after the entrypoint rules, which plan wrappers and start lines). */
    public LifecyclePlan lifecycle() {
        if (lifecycle == null) {
            lifecycle = new LifecyclePlan(this);
        }
        return lifecycle;
    }

    /** MDC placement decisions with reasons (REPEATED_LOG_VALUE), for `logast why <key>`. */
    public final List<RepeatedValueRules.Decision> mdcDecisions = new ArrayList<>();
    String supportOrderId;
    /** #123: SetupRules found no build file (vendor mode then copies the support classes on first use). */
    boolean noBuildFile;
    private PathContext planned;

    RuleContext(Analysis a) {
        this.a = a;
        this.policy = a.policy;
        this.domain = new DomainValues(policy);
        this.glossary = new Glossary(policy);
        this.phrasing = new Phrasing(policy);
        a.ledger.clear();
    }

    /** {@code lc} is passed over on purpose: the coverage report says why (#72). */
    void skip(dev.logast.model.LogCall lc, String reason) {
        a.ledger.skip(lc.call(), reason);
    }

    /** True once the support starter is present or an order adds it. */
    public boolean starterPlanned() {
        return a.supportStarterPresent || supportOrderId != null;
    }

    /**
     * True once a TaskDecorator carries MDC into executors, or the starter's will: the set-up switches it on with the
     * starter when ASYNC_CONTEXT_LOSS runs (logging-support.context-propagation.enabled, #93).
     */
    boolean decoratorPlanned() {
        return a.decoratorPresent || starterPlanned() && enabled(Rules.ASYNC_CONTEXT_LOSS) && enabled(Rules.SETUP_SUPPORT_STARTER);
    }

    /** Called after the entrypoint rules: MDC context as it will be once their orders are applied. */
    void freezePlan() {
        planned = new PathContext(a.graph, a.entrypoints, new TreeSet<>(policy.mdc().ambientKeys()),
                a.httpFilterKeys, decoratorPlanned(), entry.entryKeys(), mdc.ownKeys())
                .withCanonicalKeys(dev.logast.Analysis.canonicalKeys(policy), new TreeSet<>(policy.mdc().ambientKeys()),
                        a.httpFilterKeys, decoratorPlanned());
    }

    /** MDC keys guaranteed on entry to {@code m}, with the planned entrypoint/whole-method scopes applied. */
    public Set<String> mdcAtEntry(MethodInfo m) {
        return (planned != null ? planned : a.paths).mdcAtEntry(m);
    }

    public Set<String> mdcInside(MethodInfo m) {
        Set<String> local = mdc.localKeys().get(m.key());
        Set<String> s = (planned != null ? planned : a.paths).mdcInside(m);
        if (local == null) {
            return s;
        }
        Set<String> out = new TreeSet<>(s);
        out.addAll(local);
        return out;
    }

    /** Id of the order planned to put {@code key} into MDC inside {@code m}, or null. */
    public String plannedBy(MethodInfo m, String key) {
        return a.paths.mdcInside(m).contains(key) ? null : mdc.by().get(m.key() + "|" + key);
    }

    public boolean plannedKey(MethodInfo m, String key) {
        return !a.paths.mdcInside(m).contains(key) && mdcInside(m).contains(key);
    }

    /**
     * The method still writes MDC the old way (put/remove outside try-with-resources, clear, setContextMap). New
     * scopes or observations are not added around such code: a clear() inside would wipe them. The MDC orders come
     * first; the wraps follow after the rescan.
     */
    public boolean legacyMdc(MethodInfo m) {
        return m.facts().mdc.stream().anyMatch(op -> switch (op.kind()) {
            case CLEAR, SET_CONTEXT_MAP -> true;
            case PUT, REMOVE -> !op.inResource();
            default -> false;
        });
    }

    private static final java.util.regex.Pattern HELPER_CLASS = java.util.regex.Pattern.compile("(?i).*(log|logging)(helper|helpers|util|utils|support|writer)?");

    /**
     * A method that logs on behalf of its callers: a Logger parameter, or a static logging method of a *Log*Helper
     * class. LOG_HELPER reports it (inline it); the rewriting rules leave its body alone.
     */
    public boolean logHelper(MethodInfo m) {
        if (m.decl().getBody() == null || m.facts().logs.isEmpty()) {
            return false;
        }
        boolean loggerParam = m.decl().getParameters().stream().anyMatch(p -> p instanceof org.openrewrite.java.tree.J.VariableDeclarations vd
                && org.openrewrite.java.tree.TypeUtils.isOfClassType(vd.getType(), "org.slf4j.Logger"));
        return loggerParam || HELPER_CLASS.matcher(m.owner().simpleName()).matches()
                && m.type().hasFlags(org.openrewrite.java.tree.Flag.Static);
    }

    /** Rule enabled in the policy and its pack selected. */
    public boolean enabled(String rule) {
        return Rules.enabled(policy, rule);
    }

    /** Policy area of the method's class, or null. */
    public String area(MethodInfo m) {
        return policy.areaOf(m.owner().fqn());
    }

    /** Classes outside the support package. */
    public java.util.List<dev.logast.model.ClassInfo> methodsOwners() {
        String prefix = a.supportPackage + ".";
        return a.index.classes().stream().filter(c -> !c.fqn().startsWith(prefix)).toList();
    }

    /** Methods rules look at: everything except the support classes themselves (vendored copies included). */
    private java.util.List<MethodInfo> methods;

    /** The service's own methods: the logging-support classes copied into it are not checked (their log calls are skipped). */
    public java.util.List<MethodInfo> methods() {
        if (methods == null) {
            String prefix = a.supportPackage + ".";
            java.util.List<MethodInfo> own = new ArrayList<>();
            for (MethodInfo m : a.index.methods()) {
                if (m.owner().fqn().startsWith(prefix)) {
                    m.facts().logs.forEach(lc -> skip(lc, dev.logast.LogLedger.SUPPORT));
                } else {
                    own.add(m);
                }
            }
            methods = java.util.List.copyOf(own);
        }
        return methods;
    }

    public String support(String simpleName) {
        return a.supportPackage + "." + simpleName;
    }

    public OrderBuilder order(String rule, String anchor) {
        OrderBuilder b = new OrderBuilder(rule, anchor, Rules.severity(policy, rule));
        orders.add(b);
        return b;
    }

    /** Standard order for a finding inside a method. */
    public OrderBuilder order(String rule, MethodInfo m, int line, String anchor) {
        OrderBuilder b = order(rule, m.key() + "|" + anchor).at(m.file().relPath(), line);
        b.method = m.label();
        b.paths = a.paths.paths(m, 3);
        return b;
    }

    /**
     * The n-th finding of {@code kind} in the method, numbered in source order (line, then the order the rule found
     * them). Unlike a line number in the anchor, the id stays the same when lines are added or removed around it, and
     * two findings on one line get different ids.
     */
    public OrderBuilder orderNth(String rule, MethodInfo m, int line, String kind) {
        OrderBuilder b = order(rule, m, line, kind + "#?");
        b.ordinalGroup = rule + "|" + m.key() + "|" + kind;
        b.ordinalLine = line;
        return b;
    }

    /** {@link #orderNth(String, MethodInfo, int, String)} outside a method: {@code owner} is the class (or file) it belongs to. */
    public OrderBuilder orderNth(String rule, String owner, String file, int line, String kind) {
        OrderBuilder b = order(rule, owner + "|" + kind + "#?").at(file, line);
        b.ordinalGroup = rule + "|" + owner + "|" + kind;
        b.ordinalLine = line;
        return b;
    }

    public void drop(OrderBuilder b) {
        orders.remove(b);
    }

    public WorkOrder.Context context(MethodInfo m, int offset, String code, String argsSlot) {
        Map<String, String> vars = new LinkedHashMap<>();
        ScopeResolver.varsAt(m, offset, false).forEach((k, v) -> vars.put(k, Types.display(v)));
        return new WorkOrder.Context(List.copyOf(mdcInside(m)), vars, code, argsSlot);
    }

    /**
     * Names a generated variable must not use: the variables in scope at {@code offset} and every variable declared
     * anywhere in the method (a wrap around existing code must not reuse a name declared inside it).
     */
    public Set<String> taken(MethodInfo m, int offset) {
        Set<String> out = new HashSet<>(vars(m, offset).keySet());
        if (m.decl().getBody() != null) {
            new org.openrewrite.java.JavaIsoVisitor<Integer>() {
                @Override
                public org.openrewrite.java.tree.J.VariableDeclarations.NamedVariable visitVariable(
                        org.openrewrite.java.tree.J.VariableDeclarations.NamedVariable v, Integer p) {
                    out.add(v.getSimpleName());
                    return super.visitVariable(v, p);
                }
            }.visit(m.decl().getBody(), 0);
        }
        return out;
    }

    public Map<String, JavaType> vars(MethodInfo m, int offset) {
        return ScopeResolver.varsAt(m, offset);
    }

    /**
     * {@code // logast:ignore} or {@code // logast:ignore RULE} on the line or the line above, or
     * {@code // logast:ignore-file RULE} anywhere in the file (see {@link Suppressions}).
     */
    public boolean ignored(SourceFileInfo f, int line, String rule) {
        boolean ignored = fileIgnores.computeIfAbsent(f.relPath(), k -> Suppressions.fileIgnores(f.text())).contains(rule)
                || Suppressions.lineIgnores(f.lineText(line), rule) || line > 1 && Suppressions.lineIgnores(f.lineText(line - 1), rule);
        if (ignored) {
            a.ledger.ignored(f.relPath(), line, rule);   // the coverage report shows the log call there as ignored (#72)
        }
        return ignored;
    }

    public void dependOnStarter(OrderBuilder b) {
        if (!Rules.SUPPORT_USERS.contains(b.rule)) {
            // its packs would not bring the support set-up: selected alone, the fix would not compile (#87)
            throw new IllegalStateException("rule " + b.rule + " uses the support classes but is not in Rules.SUPPORT_USERS");
        }
        if (!a.supportStarterPresent && supportOrderId == null && noBuildFile && policy.support().vendor()
                && enabled(Rules.SETUP_SUPPORT_STARTER)) {
            SetupRules.vendorWithoutBuildFile(this);   // #123: the fix imports the classes: copy them in first
        }
        if (!a.supportStarterPresent || supportUpdateOrderId != null) {
            b.dependsOn(supportUpdateOrderId != null ? supportUpdateOrderId : supportOrderId);
        }
    }

    /**
     * How to get a logger into {@code owner}: the SLF4J logger in scope (own, inherited or enclosing), none when the
     * class logs through another API, else Lombok's @Slf4j when Lombok is there, or a field.
     */
    JavaEdits.LoggerPlan logger(dev.logast.model.ClassInfo owner) {
        return JavaEdits.logger(owner, a.index.loggers().nearest(owner), policy.logging().loggerName(), lombokLoggers());
    }

    /**
     * The logger for lines written into {@code m}: as {@link #logger(dev.logast.model.ClassInfo)}, but when a parameter
     * or local variable anywhere in {@code m} is named like the logger, the name is qualified ({@code Owner.log},
     * {@code this.log}), as the variable would hide the field where a line lands in its scope (#83). Without a
     * qualified name the plan has no logger, so the rules report without editing.
     */
    JavaEdits.LoggerPlan logger(MethodInfo m) {
        JavaEdits.LoggerPlan plan = logger(m.owner());
        return plan.name() != null && declares(m, plan.name()) ? plan.qualify() : plan;
    }

    /** A parameter, local, catch or lambda parameter of {@code m} (nested classes included) is named {@code name}. */
    private static boolean declares(MethodInfo m, String name) {
        boolean[] found = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public org.openrewrite.java.tree.J.VariableDeclarations.NamedVariable visitVariable(
                    org.openrewrite.java.tree.J.VariableDeclarations.NamedVariable v, Integer p) {
                found[0] |= v.getSimpleName().equals(name);
                return super.visitVariable(v, p);
            }
        }.visit(m.decl(), 0);
        return found[0];
    }

    /**
     * Returns a Mono, Flux or other Reactive Streams publisher: the method assembles a pipeline that runs later, on
     * other threads, so a scope or wrapper around its body ends before the work starts.
     */
    boolean reactive(MethodInfo m) {
        return m.decl().getMethodType() != null
                && org.openrewrite.java.tree.TypeUtils.isAssignableTo("org.reactivestreams.Publisher", m.decl().getMethodType().getReturnType());
    }

    /** Methods of job/listener flows (metrics.defaults "jobs"), computed once per scan. */
    Set<String> jobFlow;

    private ValueTrace values;

    /** Whether a logged value is an MDC key's value, followed back through locals and callers. */
    ValueTrace values() {
        if (values == null) {
            values = new ValueTrace(this);
        }
        return values;
    }

    private Boolean lombokLoggers;

    /**
     * New loggers are Lombok's @Slf4j when the service uses it: on the classpath, or (a copied classpath that misses
     * the jar) already imported by its own sources.
     */
    boolean lombokLoggers() {
        if (lombokLoggers == null) {
            lombokLoggers = a.project.probe().hasClass("lombok.extern.slf4j.Slf4j")
                    || a.project.files().stream().anyMatch(f -> f.text().contains("import lombok.extern.slf4j.Slf4j;"));
        }
        return lombokLoggers;
    }

    /** A support class generated code may use: added or updated by this run, or already there. */
    private static final java.util.regex.Pattern PACKAGE = java.util.regex.Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");

    /**
     * #248: whether a support class's simple name is free in a file: when the service has its own type of that name in
     * the file's package (its own {@code AfterCommit}), an import of the support class would shadow it in the whole file;
     * when the file imports another type of that name, a second import does not compile.
     */
    /**
     * #285: calls per day of the busiest entrypoint that reaches {@code m} (its traffic value, a pattern, or its
     * schedule); startup events and runs without a schedule count as rare. Null when none reaches it or one of them has
     * no known rate: a line added there would be INFO on a path nobody measured.
     */
    Double busiestRate(MethodInfo m) {
        List<dev.logast.model.Entrypoint> eps = a.paths.entrypointsReaching(m);
        if (eps.isEmpty()) {
            return null;
        }
        double max = 0;
        for (dev.logast.model.Entrypoint ep : eps) {
            Double r = a.traffic.perDay(ep);
            if (r == null && !MetricsMap.lowFrequency(ep)) {
                return null;
            }
            max = Math.max(max, r == null ? 1 : r);
        }
        return max;
    }

    boolean supportNameFree(String simpleName, dev.logast.parse.SourceFileInfo f) {
        java.util.regex.Matcher m = PACKAGE.matcher(f.text());
        String pkg = m.find() ? m.group(1) : "";
        java.util.regex.Matcher imported = java.util.regex.Pattern.compile("(?m)^import\\s+([\\w.]+)\\." + simpleName + "\\s*;").matcher(f.text());
        while (imported.find()) {
            if (!imported.group(1).equals(a.supportPackage)) {
                return false;
            }
        }
        return pkg.equals(a.supportPackage) || a.index.clazz(pkg.isEmpty() ? simpleName : pkg + "." + simpleName) == null;
    }

    boolean supportHas(String simpleName) {
        if (!a.supportStarterPresent || supportUpdateOrderId != null) {
            return true;
        }
        return a.index.clazz(support(simpleName)) != null || a.project.probe().hasClass(support(simpleName));
    }

    /** The order that brings already-copied (vendor mode) support classes up to date, if any. */
    String supportUpdateOrderId;

    /**
     * Whether generated code may use {@code ObservedContext…run(() -> …)}: the support classes are added or updated
     * by this run, or the ones there (copied or on the classpath) already have it.
     */
    boolean supportHasRun() {
        if (!a.supportStarterPresent || supportUpdateOrderId != null) {
            return true;
        }
        String fqn = a.supportPackage + ".ObservedContext";
        var cls = a.index.clazz(fqn);
        if (cls != null) {
            return cls.file().text().contains("interface Work");
        }
        return a.project.probe().hasClass(fqn + "$Work");
    }
}
