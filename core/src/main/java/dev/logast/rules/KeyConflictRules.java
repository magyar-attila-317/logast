package dev.logast.rules;

import dev.logast.model.LogCall;
import dev.logast.model.MdcOp;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.util.*;

/**
 * LOG_KEY_NESTING_CONFLICT (#18, report, medium): the service logs a field {@code x} and a field {@code x.y}.
 * Elasticsearch expands dotted field names into objects (Elasticsearch reference, "object" field type / subobjects:
 * a field named {@code order.id} is mapped as the object {@code order} with the field {@code id}), and Spring Boot's
 * ECS format writes dotted MDC keys nested since 3.5 (its ContextPairs.nested): either way {@code order} becomes an
 * object, and a document carrying a plain {@code order} value is rejected (a mapping conflict) — its log line is lost.
 * Keys come from the vocabulary, MDC in code ({@code MDC.put} / {@code MdcScope.of}), {@code [k=v]} suffixes and
 * fluent {@code addKeyValue} properties.
 */
final class KeyConflictRules {
    /** Where a key is used: "file:line" (or "logast.yaml"), and the method, for the order's anchor. */
    private record Use(String where, MethodInfo method, int line) {
    }

    private final RuleContext ctx;

    KeyConflictRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.LOG_KEY_NESTING_CONFLICT)) {
            return;
        }
        Map<String, List<Use>> uses = new TreeMap<>();
        for (String key : ctx.policy.mdcRegistry().keySet()) {
            uses.computeIfAbsent(key, k -> new ArrayList<>()).add(new Use("logast.yaml (vocabulary)", null, 0));
            String logKey = ctx.policy.logKey(key);
            if (!logKey.equals(key)) {
                uses.computeIfAbsent(logKey, k -> new ArrayList<>()).add(new Use("logast.yaml (vocabulary logKey)", null, 0));
            }
        }
        for (MethodInfo m : ctx.methods()) {
            for (MdcOp op : m.facts().mdc) {
                if (op.key() != null && (op.kind() == MdcOp.Kind.PUT || op.kind() == MdcOp.Kind.PUT_CLOSEABLE)) {
                    add(uses, op.key(), m, Pos.line(op.call()));
                }
            }
            for (LogCall lc : m.facts().logs) {
                if (lc.template() instanceof J.Literal l && l.getValue() instanceof String raw) {
                    List<String> keys = MessageTemplate.compliantKeys(raw);
                    List<Expression> args = lc.args();
                    for (int i = 0; keys != null && i < keys.size(); i++) {
                        // a whole object as the value is LOG_OBJECT_ARGUMENT's: its fix logs the object's id under another key
                        if (i < args.size() && objectValue(args.get(i))) {
                            continue;
                        }
                        add(uses, keys.get(i), m, Pos.line(lc.call()));
                    }
                }
                for (Expression e = lc.call().getSelect(); e instanceof J.MethodInvocation mi; e = mi.getSelect()) {
                    if (mi.getSimpleName().equals("addKeyValue") && !mi.getArguments().isEmpty()
                            && mi.getArguments().getFirst() instanceof J.Literal k && k.getValue() instanceof String key) {
                        add(uses, key, m, Pos.line(mi.getName()));
                    }
                }
            }
        }
        for (String parent : uses.keySet()) {
            List<String> nested = uses.keySet().stream().filter(k -> k.startsWith(parent + ".")).toList();
            if (nested.isEmpty()) {
                continue;
            }
            List<Use> plain = uses.get(parent);
            Use anchor = plain.stream().filter(u -> u.method() != null).findFirst()
                    .or(() -> nested.stream().flatMap(n -> uses.get(n).stream()).filter(u -> u.method() != null).findFirst())
                    .orElse(null);
            if (anchor == null || ctx.ignored(anchor.method().file(), anchor.line(), Rules.LOG_KEY_NESTING_CONFLICT)) {
                continue;   // only in logast.yaml: the vocabulary check is the policy's (and has no line to point at)
            }
            String where = String.join(", ", plain.stream().map(Use::where).distinct().limit(3).toList());
            String nestedWhere = String.join("; ", nested.stream().map(n -> n + " at "
                    + String.join(", ", uses.get(n).stream().map(Use::where).distinct().limit(2).toList())).toList());
            OrderBuilder b = ctx.order(Rules.LOG_KEY_NESTING_CONFLICT, anchor.method(), anchor.line(), "keynest|" + parent)
                    .title("Log field '" + parent + "' and '" + nested.getFirst() + "'" + (nested.size() > 1 ? " (and "
                            + (nested.size() - 1) + " more)" : "") + " in one service: the search index maps " + parent
                            + " as an object for the dotted ones, so a line with a plain " + parent + " value is rejected "
                            + "(a mapping conflict) and lost")
                    .suggestion("Give the plain value a name of its own (" + parent + ".id, " + parent + ".ref, …) in the "
                            + "vocabulary and the code, or rename the dotted ones; one name per field.")
                    .basis(parent + " at " + where + "; " + nestedWhere + ". Elasticsearch expands dotted field names into "
                            + "objects (Elasticsearch reference, object field type); Spring Boot's ECS format nests dotted MDC "
                            + "keys since 3.5")
                    .doneWhen("no key " + parent + " is logged next to " + parent + ".…");
            for (String n : nested) {
                uses.get(n).stream().filter(u -> u.method() != null).findFirst()
                        .ifPresent(u -> b.location(u.method().file().relPath(), u.line(), Rules.LOG_KEY_NESTING_CONFLICT));
            }
        }
    }

    /** A value that is an object of the service's or a library's own type (not text, a number, an id, a date, an enum). */
    private static boolean objectValue(Expression e) {
        org.openrewrite.java.tree.JavaType t = e.getType();
        if (t == null || t instanceof org.openrewrite.java.tree.JavaType.Primitive || t instanceof org.openrewrite.java.tree.JavaType.Unknown) {
            return false;
        }
        org.openrewrite.java.tree.JavaType.FullyQualified fq = org.openrewrite.java.tree.TypeUtils.asFullyQualified(t);
        if (fq == null || fq.getKind() == org.openrewrite.java.tree.JavaType.FullyQualified.Kind.Enum) {
            return false;
        }
        String n = fq.getFullyQualifiedName();
        return !(n.startsWith("java.lang.") || n.startsWith("java.time.") || n.startsWith("java.math.") || n.equals("java.util.UUID"));
    }

    private static void add(Map<String, List<Use>> uses, String key, MethodInfo m, int line) {
        uses.computeIfAbsent(key, k -> new ArrayList<>()).add(new Use(m.file().relPath() + ":" + line, m, line));
    }
}
