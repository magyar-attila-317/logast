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
    final Map<String, Set<String>> plannedEntryKeys = new HashMap<>();
    /** Keys a planned wrap sets for a whole method body (passed on to callees). */
    final Map<String, Set<String>> plannedOwnKeys = new HashMap<>();
    /** Keys a planned wrap sets after a local declaration: valid inside the method only. */
    final Map<String, Set<String>> plannedLocalKeys = new HashMap<>();
    /** "methodKey|mdcKey" → id of the order that will put it in MDC (message rewrites that drop it depend on it). */
    final Map<String, String> plannedBy = new HashMap<>();
    /** Entrypoint method key → id of the order that scopes its domain MDC keys. */
    final Map<String, String> entryOrders = new HashMap<>();
    /** Entrypoints that ENTRYPOINT_SILENT gives a "started" line. */
    final Set<String> silentPlanned = new HashSet<>();
    private LifecyclePlan lifecycle;

    /** Who owns which lifecycle line of every flow (built after the entrypoint rules, which plan wrappers and start lines). */
    public LifecyclePlan lifecycle() {
        if (lifecycle == null) {
            lifecycle = new LifecyclePlan(this);
        }
        return lifecycle;
    }

    /** MDC placement decisions with reasons (REPEATED_LOG_VALUE), for `logast why <key>`. */
    public final List<RepeatedValueRules.Decision> mdcDecisions = new ArrayList<>();
    /** Methods whose body an entrypoint order already rewraps. */
    final Set<String> wrappedMethods = new HashSet<>();
    String supportOrderId;
    /** TracingRules will switch on Kafka listener observation, so listeners count as observed. */
    boolean kafkaListenerObservationPlanned;
    private PathContext planned;

    RuleContext(Analysis a) {
        this.a = a;
        this.policy = a.policy;
        this.domain = new DomainValues(policy);
        this.glossary = new Glossary(policy);
        this.phrasing = new Phrasing(policy);
    }

    /** True once the support starter is present or an order adds it. */
    public boolean starterPlanned() {
        return a.supportStarterPresent || supportOrderId != null;
    }

    /** Called after the entrypoint rules: MDC context as it will be once their orders are applied. */
    void freezePlan() {
        planned = new PathContext(a.graph, a.entrypoints, new TreeSet<>(policy.mdc().ambientKeys()),
                a.httpFilterKeys, a.decoratorPresent || starterPlanned(), plannedEntryKeys, plannedOwnKeys)
                .withCanonicalKeys(dev.logast.Analysis.canonicalKeys(policy), new TreeSet<>(policy.mdc().ambientKeys()),
                        a.httpFilterKeys, a.decoratorPresent || starterPlanned());
    }

    /** MDC keys guaranteed on entry to {@code m}, with the planned entrypoint/whole-method scopes applied. */
    public Set<String> mdcAtEntry(MethodInfo m) {
        return (planned != null ? planned : a.paths).mdcAtEntry(m);
    }

    public Set<String> mdcInside(MethodInfo m) {
        Set<String> local = plannedLocalKeys.get(m.key());
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
        return a.paths.mdcInside(m).contains(key) ? null : plannedBy.get(m.key() + "|" + key);
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
    public java.util.List<MethodInfo> methods() {
        String prefix = a.supportPackage + ".";
        return a.index.methods().stream().filter(m -> !m.owner().fqn().startsWith(prefix)).toList();
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

    /** {@code // logast:ignore} or {@code // logast:ignore RULE} on the line or the line above. */
    public boolean ignored(SourceFileInfo f, int line, String rule) {
        for (String l : List.of(f.lineText(line), line > 1 ? f.lineText(line - 1) : "")) {
            int i = l.indexOf("logast:ignore");
            if (i >= 0) {
                String rest = l.substring(i + "logast:ignore".length()).trim();
                if (rest.isEmpty() || rest.contains(rule)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void dependOnStarter(OrderBuilder b) {
        if (!a.supportStarterPresent || supportUpdateOrderId != null) {
            b.dependsOn(supportUpdateOrderId != null ? supportUpdateOrderId : supportOrderId);
        }
    }

    /** How to get a logger into {@code owner}: the existing one, Lombok's @Slf4j when Lombok is there, or a field. */
    JavaEdits.LoggerPlan logger(dev.logast.model.ClassInfo owner) {
        return JavaEdits.logger(owner, policy.logging().loggerName(), a.project.probe().hasClass("lombok.extern.slf4j.Slf4j"));
    }

    /** A support class generated code may use: added or updated by this run, or already there. */
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
