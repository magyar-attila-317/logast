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
                    String expr = valueOf(v.getKey(), v.getValue());
                    if (expr != null) {   // a value object without a readable value is left out, not logged as Foo[...]
                        found = new Value(key, expr);
                        break;
                    }
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

    /**
     * {@link #fromVars} for an entrypoint's parameters (#15), plus the values a request or message object carries: a key
     * no parameter provides is read from a parameter's getter or record accessor named like one of the key's source
     * parameters ({@code request.getPledgeId()} of a protobuf request, {@code cmd.orderId()} of a record). Kept narrow:
     * registry keys only (the vocabulary's {@code sources.params} names, nothing guessed from other names), one level
     * (no {@code a.getB().getC()}), a public no-argument instance method declared by the parameter's own type (or a
     * record component), and a value that prints well (text, number, UUID, enum). JDK types are never searched.
     */
    public List<Value> fromParams(Map<String, JavaType> params) {
        List<Value> out = new ArrayList<>(fromVars(params));
        Set<String> found = new HashSet<>(out.stream().map(Value::key).toList());
        for (var e : policy.mdcRegistry().entrySet()) {
            if (found.contains(e.getKey())) {
                continue;
            }
            for (var v : params.entrySet()) {
                String accessor = accessor(v.getValue(), e.getValue().params());
                if (accessor != null) {
                    out.add(new Value(e.getKey(), v.getKey() + "." + accessor));
                    break;
                }
            }
        }
        return out;
    }

    /** {@code getPledgeId()} / {@code pledgeId()} of {@code type} for one of {@code names}, or null. */
    static String accessor(JavaType type, Collection<String> names) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq == null || type instanceof JavaType.Unknown || fq.getFullyQualifiedName().startsWith("java.") || scalar(type)) {
            return null;
        }
        for (String name : names) {
            if (name.isEmpty()) {
                continue;
            }
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (JavaType.Method mt : fq.getMethods()) {
                if ((mt.getName().equals(getter) || mt.getName().equals(name)) && mt.getParameterTypes().isEmpty()
                        && mt.hasFlags(org.openrewrite.java.tree.Flag.Public) && !mt.hasFlags(org.openrewrite.java.tree.Flag.Static)
                        && scalar(mt.getReturnType())) {
                    return mt.getName() + "()";
                }
            }
            if (fq.getKind() == JavaType.FullyQualified.Kind.Record) {   // implicit accessors: the components
                for (JavaType.Variable f : fq.getMembers()) {
                    if (f.getName().equals(name) && !f.hasFlags(org.openrewrite.java.tree.Flag.Static) && scalar(f.getType())) {
                        return name + "()";
                    }
                }
            }
        }
        return null;
    }

    private static final Set<String> SCALARS =Set.of("java.lang.String", "java.lang.CharSequence", "java.lang.Long",
            "java.lang.Integer", "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean",
            "java.lang.Double", "java.lang.Float", "java.util.UUID", "java.math.BigInteger", "java.math.BigDecimal");

    /**
     * What to put into MDC for a variable named like a key (#49): the variable when its value prints well (text,
     * number, UUID, enum), {@code x.orElse(null)} for an Optional of one, {@code x.value()} for a record wrapping one,
     * otherwise null (not usable). An unresolved type is taken as it is: no classpath must not lose MDC keys.
     */
    static String valueOf(String name, JavaType type) {
        if (type == null || type instanceof JavaType.Unknown || type instanceof JavaType.Primitive || scalar(type)) {
            return name;
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq == null) {
            return null;
        }
        if (fq.getFullyQualifiedName().equals("java.util.Optional")) {
            List<JavaType> params = type instanceof JavaType.Parameterized p ? p.getTypeParameters() : List.of();
            return !params.isEmpty() && scalar(params.getFirst()) ? name + ".orElse(null)" : null;
        }
        if (fq.getKind() == JavaType.FullyQualified.Kind.Record) {
            List<JavaType.Variable> fields = fq.getMembers().stream().filter(f -> !f.hasFlags(org.openrewrite.java.tree.Flag.Static)).toList();
            if (fields.size() == 1 && scalar(fields.getFirst().getType())) {
                return name + "." + fields.getFirst().getName() + "()";
            }
        }
        return null;
    }

    private static boolean scalar(JavaType t) {
        if (t instanceof JavaType.Primitive) {
            return true;
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
        return fq != null && (SCALARS.contains(fq.getFullyQualifiedName()) || fq.getKind() == JavaType.FullyQualified.Kind.Enum);
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
        // an accessor named like a source parameter carries that key: request.pledgeId(), event.getPledgeId(), cmd.pledgeId
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
    /** Calls that only reshape a value: it is named after what they start from. */
    private static final Set<String> TRANSFORMS = Set.of("toLowerCase", "toUpperCase", "trim", "strip", "toString", "name",
            "toPlainString", "intern");

    /** Stream and collection steps: a value they produce is named after what the pipeline starts from. */
    private static final Set<String> PIPELINE = Set.of("toList", "collect", "stream", "map", "filter", "sorted", "distinct",
            "limit", "values", "keySet", "entrySet", "toArray", "flatMap", "boxed", "mapToObj");

    public static String derivedKey(Expression arg) {
        if (arg instanceof J.Identifier id) {
            return id.getSimpleName();
        }
        if (arg instanceof J.FieldAccess fa) {
            return fa.getSimpleName();
        }
        if (arg instanceof J.MethodInvocation mi) {
            String n = mi.getSimpleName();
            // "orders.stream().map(Order::getUuid).toList()": a pipeline is named after what it starts from, not after
            // toList/collect/stream; a projection to ids says so ("orderIds")
            if (PIPELINE.contains(n) && mi.getSelect() != null) {
                Expression root = mi.getSelect();
                boolean ids = arg.printTrimmed().matches("(?s).*\\.map\\(\\s*\\w+::get(Id|Uuid)\\s*\\).*");
                while (root instanceof J.MethodInvocation inner && PIPELINE.contains(inner.getSimpleName()) && inner.getSelect() != null) {
                    root = inner.getSelect();
                }
                String base = derivedKey(root);
                if (base != null && !PIPELINE.contains(base)) {
                    return ids ? singular(base) + "Ids" : base;
                }
            }
            // G2: e.getClass().getSimpleName() names the error's type, not "simpleName"
            if (n.equals("getSimpleName") && mi.getSelect() instanceof J.MethodInvocation cls && cls.getSimpleName().equals("getClass")) {
                String owner = cls.getSelect() == null ? null : derivedKey(cls.getSelect());
                return owner == null || owner.matches("e|ex|exc|exception|t|th|throwable|err|error|cause") ? "errorType" : owner + "Type";
            }
            // G2: a transformation of a value is named after the value (kind.toLowerCase(…) → kind)
            if (TRANSFORMS.contains(n) && mi.getSelect() != null) {
                String inner = derivedKey(mi.getSelect());
                if (inner != null) {
                    return inner;
                }
            }
            if (n.startsWith("get") && n.length() > 3 && Character.isUpperCase(n.charAt(3))) {
                n = Character.toLowerCase(n.charAt(3)) + n.substring(4);
            }
            // G1: the id of what a getter returns is that thing's id (token.getUser().getId() → userId, not id)
            if (Set.of("id", "uuid", "name", "code", "key").contains(n) && mi.getSelect() instanceof J.MethodInvocation owner) {
                String base = derivedKey(owner);
                if (base != null && !base.isEmpty()) {
                    return base + Character.toUpperCase(n.charAt(0)) + n.substring(1);
                }
            }
            if ((n.equals("size") || n.equals("length") || n.equals("count")) && mi.getSelect() instanceof J.Identifier sel) {
                return countKey(sel.getSimpleName());   // items.size() -> itemCount, pledgeIds.size() -> pledgeCount
            }
            if (Set.of("id", "name", "type", "status", "code", "key", "value").contains(n)
                    && mi.getSelect() instanceof J.Identifier sel) {
                return sel.getSimpleName() + Character.toUpperCase(n.charAt(0)) + n.substring(1);
            }
            return n;
        }
        return null;
    }

    /** Key for the size of a collection named {@code name}: items → itemCount, pledgeIds → pledgeCount. */
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
        // G1: an identifier (tokenDeviceId, emailTemplateId) names a record, not the personal value: not PII unless the
        // term itself is an identifier (taxId)
        List<String> w = words(name);
        if (!w.isEmpty() && Set.of("id", "ids", "uuid", "uuids").contains(w.getLast())) {
            return namesTerm(name, policy.pii().stream().filter(t -> {
                List<String> tw = words(t);
                return !tw.isEmpty() && Set.of("id", "ids", "uuid", "number").contains(tw.getLast());
            }).toList());
        }
        return namesTerm(name, policy.pii());
    }

    /** {@code name}'s words contain one of the terms' words in a row (the whole-word match of {@link #isPiiName}). */
    static boolean namesTerm(String name, Collection<String> terms) {
        List<String> words = words(name);
        for (String term : terms) {
            List<String> t = words(term);
            for (int i = 0; i + t.size() <= words.size(); i++) {
                if (words.subList(i, i + t.size()).equals(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Secrets: credentials, tokens, full card numbers and IBANs. Never belong in a log line or an audit record. */
    static final List<String> SECRET_TERMS = List.of("password", "passwd", "pwd", "secret", "token", "apiKey",
            "credential", "credentials", "privateKey", "cvv", "cvc", "pin", "cardNumber", "pan", "iban");
    /** Words that say the value is derived from the secret, not the secret: tokenId, maskedIban, passwordHash. */
    private static final Set<String> NOT_THE_SECRET = Set.of("id", "ids", "count", "length", "size", "type", "expiry",
            "expires", "expired", "at", "version", "status", "masked", "mask", "redacted", "last4", "hint", "hash", "hashed",
            "changed", "reset", "policy", "name");

    /**
     * The value is a secret by its name (a variable, field or accessor; the PII name matching with the secret terms),
     * and not a constant or a value derived from one (tokenId, maskedIban, passwordHash).
     */
    public boolean isSecret(Expression arg) {
        if (isConstant(arg)) {
            return false;
        }
        String name = derivedKey(arg);
        return name != null && isSecretName(name);
    }

    static boolean isSecretName(String name) {
        return namesTerm(name, SECRET_TERMS) && words(name).stream().noneMatch(NOT_THE_SECRET::contains);
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
