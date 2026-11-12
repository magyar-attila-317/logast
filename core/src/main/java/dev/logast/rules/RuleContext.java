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
                a.httpFilterKeys, a.decoratorPresent || starterPlanned(), plannedEntryKeys);
    }

    public Set<String> mdcInside(MethodInfo m) {
        return (planned != null ? planned : a.paths).mdcInside(m);
    }

    public boolean plannedKey(MethodInfo m, String key) {
        return !a.paths.mdcInside(m).contains(key) && mdcInside(m).contains(key);
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
        if (!a.supportStarterPresent) {
            b.dependsOn(supportOrderId);
        }
    }
}
