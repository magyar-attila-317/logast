package dev.logast.rules;

import dev.logast.graph.Types;
import dev.logast.policy.Policy;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/** Maps variables and log arguments to MDC registry keys (policy {@code mdc.keys}). */
public final class DomainValues {
    /** @param expr source expression yielding the value, e.g. {@code order.id()} */
    public record Value(String key, String expr) {
    }

    private final Policy policy;

    public DomainValues(Policy policy) {
        this.policy = policy;
    }

    /** For each registry key, the first variable in scope that provides it. */
    public List<Value> fromVars(Map<String, JavaType> vars) {
        List<Value> out = new ArrayList<>();
        for (var e : policy.mdcRegistry().entrySet()) {
            String key = e.getKey();
            Policy.KeySource src = e.getValue();
            Value found = null;
            for (var v : vars.entrySet()) {
                if (src.params().contains(v.getKey())) {
                    found = new Value(key, v.getKey());
                    break;
                }
            }
            if (found == null) {
                for (var v : vars.entrySet()) {
                    String accessor = src.types().get(Types.erasure(v.getValue()));
                    if (accessor != null) {
                        found = new Value(key, v.getKey() + "." + accessor);
                        break;
                    }
                }
            }
            if (found != null) {
                out.add(found);
            }
        }
        return out;
    }

    /** Registry key an argument expression corresponds to, if any. */
    public Optional<String> keyOf(Expression arg) {
        for (var e : policy.mdcRegistry().entrySet()) {
            Policy.KeySource src = e.getValue();
            if (arg instanceof J.Identifier id && src.params().contains(id.getSimpleName())) {
                return Optional.of(e.getKey());
            }
            if (arg instanceof J.MethodInvocation mi && mi.getSelect() != null) {
                String accessor = src.types().get(Types.erasure(mi.getSelect().getType()));
                String call = mi.getSimpleName() + "()";
                if (accessor != null && accessor.equals(call) && FactsArgs.noArgs(mi)) {
                    return Optional.of(e.getKey());
                }
            }
            if (arg instanceof J.FieldAccess fa) {
                String accessor = src.types().get(Types.erasure(fa.getTarget().getType()));
                if (accessor != null && accessor.equals(fa.getSimpleName())) {
                    return Optional.of(e.getKey());
                }
            }
        }
        // an accessor named like a source parameter carries that key: request.couponId(), event.getCouponId(), cmd.couponId
        String name = arg instanceof J.MethodInvocation mi && mi.getSelect() != null && FactsArgs.noArgs(mi) ? mi.getSimpleName()
                : arg instanceof J.FieldAccess fa ? fa.getSimpleName() : null;
        if (name != null) {
            String bare = name.startsWith("get") && name.length() > 3 && Character.isUpperCase(name.charAt(3))
                    ? Character.toLowerCase(name.charAt(3)) + name.substring(4) : name;
            for (var e : policy.mdcRegistry().entrySet()) {
                if (e.getValue().params().contains(bare)) {
                    return Optional.of(e.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /** Name used inside {@code [k=v]} for an argument without a registry key. */
    public static String derivedKey(Expression arg) {
        if (arg instanceof J.Identifier id) {
            return id.getSimpleName();
        }
        if (arg instanceof J.FieldAccess fa) {
            return fa.getSimpleName();
        }
        if (arg instanceof J.MethodInvocation mi) {
            String n = mi.getSimpleName();
            if (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3))) {
                n = Character.toLowerCase(n.charAt(3)) + n.substring(4);
            }
            if ((n.equals("size") || n.equals("length") || n.equals("count")) && mi.getSelect() instanceof J.Identifier sel) {
                return countKey(sel.getSimpleName());   // items.size() -> itemCount, couponIds.size() -> couponCount
            }
            if (Set.of("id", "name", "type", "status", "code", "key", "value").contains(n)
                    && mi.getSelect() instanceof J.Identifier sel) {
                return sel.getSimpleName() + Character.toUpperCase(n.charAt(0)) + n.substring(1);
            }
            return n;
        }
        return null;
    }

    /** Key for the size of a collection named {@code name}: items → itemCount, couponIds → couponCount. */
    static String countKey(String name) {
        String base = singular(name).replaceFirst("(Id|ID|Uuid|UUID)$", "");
        return (base.isEmpty() ? singular(name) : base) + "Count";
    }

    /** orders → order, entries → entry, statuses → status; other words unchanged. */
    static String singular(String w) {
        if (w.endsWith("ies") && w.length() > 4) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("sses") || w.endsWith("uses") || w.endsWith("xes")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 3) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    /** True when the argument names a PII field (by identifier, accessor or field name). */
    public boolean isPii(Expression arg) {
        if (isConstant(arg)) {
            return false; // a literal or static final constant has one fixed value: not personal data
        }
        String name = derivedKey(arg);
        return name != null && isPiiName(name);
    }

    /** Literal or reference to a {@code static final} field (e.g. {@code EVICTION_REASON_PER_TOKEN_CAP}). */
    public static boolean isConstant(Expression e) {
        if (e instanceof J.Literal) {
            return true;
        }
        JavaType.Variable v = e instanceof J.Identifier id ? id.getFieldType()
                : e instanceof J.FieldAccess fa ? fa.getName().getFieldType() : null;
        return v != null && v.hasFlags(org.openrewrite.java.tree.Flag.Static, org.openrewrite.java.tree.Flag.Final);
    }

    /**
     * Whole-word match: "cardNumber" matches customerCardNumber / card_number, but "pin" does not match "shipping"
     * and "email" does not match "emailTemplateId"... unless the words line up ("emailAddress" contains "email").
     */
    public boolean isPiiName(String name) {
        List<String> words = words(name);
        for (String term : policy.pii()) {
            List<String> t = words(term);
            for (int i = 0; i + t.size() <= words.size(); i++) {
                if (words.subList(i, i + t.size()).equals(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<String> words(String s) {
        return Arrays.stream(s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").split("[^A-Za-z0-9]+"))
                .filter(w -> !w.isEmpty()).map(w -> w.toLowerCase(Locale.ROOT)).toList();
    }

    static boolean isString(JavaType t) {
        return TypeUtils.isString(t);
    }

    static final class FactsArgs {
        static boolean noArgs(J.MethodInvocation mi) {
            return mi.getArguments().stream().allMatch(a -> a instanceof J.Empty);
        }
    }
}
