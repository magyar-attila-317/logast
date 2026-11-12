package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What goes into a log call, and where log calls come from.
 * <ul>
 *   <li>LOG_COLLECTION_ARGUMENT: a collection, map or array logged whole (unbounded output, element toString may load
 *       data): log its size instead ({@code items} → {@code items.size()}), unless its size is fixed
 *       ({@code List.of(..)}, constants);</li>
 *   <li>LOG_OBJECT_ARGUMENT: an entity / DTO / payload logged whole: its toString can trigger lazy loading (a query
 *       inside a log call), dump personal data or a request body. Replaced by its id where it has one; navigation
 *       through an entity ({@code ticket.getCustomer().getName()}) is reported (lazy load, NPE);</li>
 *   <li>LOG_HELPER: a production helper that logs on behalf of its callers (a Logger parameter, or a *Log*Helper/Util
 *       class), unless it redacts/masks: the message shape and level are invisible at the call site.</li>
 * </ul>
 */
final class LogArgumentRules {
    private static final Set<String> SIMPLE = Set.of("java.lang.String", "java.lang.CharSequence", "java.lang.Boolean",
            "java.lang.Character", "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
            "java.lang.Float", "java.lang.Number", "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID",
            "java.lang.Class", "java.net.URI", "java.net.URL", "java.util.Locale", "java.util.Currency", "java.nio.file.Path",
            "java.lang.StringBuilder", "java.util.concurrent.atomic.AtomicInteger", "java.util.concurrent.atomic.AtomicLong");
    private static final List<String> PAYLOAD_TYPES = List.of("org.springframework.http.HttpHeaders",
            "org.springframework.http.HttpEntity", "org.springframework.http.RequestEntity", "org.springframework.http.ResponseEntity",
            "jakarta.servlet.http.HttpServletRequest", "jakarta.servlet.http.HttpServletResponse", "jakarta.servlet.ServletRequest",
            "javax.servlet.http.HttpServletRequest", "com.fasterxml.jackson.databind.JsonNode",
            "org.springframework.web.multipart.MultipartFile", "org.springframework.messaging.Message",
            "org.apache.kafka.clients.consumer.ConsumerRecord", "org.apache.kafka.clients.producer.ProducerRecord");
    /** Classes whose toString/getters may load lazily (embeddables load with their owner: plain values). */
    private static final Set<String> ENTITY_ANNOTATIONS = Set.of("jakarta.persistence.Entity", "javax.persistence.Entity",
            "jakarta.persistence.MappedSuperclass", "org.springframework.data.mongodb.core.mapping.Document");
    private static final Set<String> BOUNDED_FACTORIES = Set.of("of", "copyOf", "asList", "noneOf", "allOf", "range", "singletonList",
            "singleton", "emptyList", "emptySet", "emptyMap", "ofEntries");
    private static final Pattern HELPER_CLASS = Pattern.compile("(?i).*(log|logging)(helper|helpers|util|utils|support|writer)?");
    private static final Pattern JUSTIFIED = Pattern.compile("(?i)mask|redact|sanitiz|anonymi|scrub|obfuscat");

    private final RuleContext ctx;
    private int argIndex;

    LogArgumentRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        boolean collections = ctx.enabled(Rules.LOG_COLLECTION_ARGUMENT);
        boolean objects = ctx.enabled(Rules.LOG_OBJECT_ARGUMENT);
        if (collections || objects) {
            for (MethodInfo m : ctx.methods()) {
                int n = 0;
                for (LogCall lc : m.facts().logs) {
                    for (int i = 0; i < lc.args().size(); i++) {
                        Expression arg = lc.args().get(i);
                        boolean throwableLast = i == lc.args().size() - 1 && TypeUtils.isAssignableTo("java.lang.Throwable", arg.getType());
                        if (!throwableLast) {
                            argIndex = i;
                            argument(m, lc, arg, collections, objects, n++);
                        }
                    }
                }
            }
        }
        if (ctx.enabled(Rules.LOG_HELPER)) {
            helpers();
        }
    }

    private void argument(MethodInfo m, LogCall lc, Expression arg, boolean collections, boolean objects, int ordinal) {
        JavaType type = arg.getType();
        if (type == null || type instanceof JavaType.Primitive || ctx.domain.keyOf(arg).isPresent()) {
            return;
        }
        if (type instanceof JavaType.Array) {
            if (collections) {
                collection(m, lc, arg, ".length", ordinal);
            }
            return;
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq == null) {
            return;
        }
        String fqn = fq.getFullyQualifiedName();
        if (TypeUtils.isAssignableTo("java.util.Collection", type) || TypeUtils.isAssignableTo("java.util.Map", type)) {
            if (collections && !bounded(m, arg)) {
                collection(m, lc, arg, ".size()", ordinal);
            }
            return;
        }
        if (!objects || SIMPLE.contains(fqn) || fqn.startsWith("java.time.") || fq.getKind() == JavaType.FullyQualified.Kind.Enum
                || TypeUtils.isAssignableTo("java.lang.Throwable", type) || TypeUtils.isAssignableTo("java.lang.Number", type)
                || TypeUtils.isAssignableTo("java.lang.CharSequence", type) || TypeUtils.isAssignableTo("java.time.temporal.TemporalAccessor", type)) {
            return;
        }
        ClassInfo own = ctx.a.index.clazz(fqn);
        boolean entity = own != null && own.decl().getLeadingAnnotations().stream().anyMatch(a -> ENTITY_ANNOTATIONS.stream().anyMatch(e -> Annotations.is(a, e)));
        boolean payload = PAYLOAD_TYPES.stream().anyMatch(p -> fqn.equals(p) || TypeUtils.isAssignableTo(p, type));
        // navigation through an entity: ticket.getCustomer().getName()
        if (arg instanceof J.MethodInvocation mi && mi.getSelect() instanceof J.MethodInvocation inner
                && entityType(inner.getType()) && inner.getArguments().stream().allMatch(a -> a instanceof J.Empty)) {
            report(m, lc, arg, ordinal, "navigates through the entity '" + Text.oneLine(inner.printTrimmed(), 40)
                    + "' inside a log call: a lazy association loads (a query) and a null link throws",
                    "Log an id that is already loaded (" + Text.oneLine(((J.MethodInvocation) arg).getSelect().printTrimmed(), 40)
                            + " → its id), or compute the value before the log call.");
            return;
        }
        if (own == null && !payload) {
            return; // a library value type we know nothing about: leave it
        }
        if (own != null && !entity && (own.isInterface() || valueObject(own))) {
            return;   // a small value record, or a class that defines its own log representation
        }
        String accessor = own == null ? null : idAccessor(own);
        String why = entity ? "is an entity: its toString can trigger lazy loading (a database query inside a log call) and dump personal data"
                : payload ? "is a request/response payload: headers, bodies and records do not belong in logs"
                : "is logged whole: its toString dumps every field (personal data, large values) and may navigate lazy associations";
        int line = Pos.line(arg);
        if (ctx.ignored(m.file(), line, Rules.LOG_OBJECT_ARGUMENT)) {
            return;
        }
        String src = m.file().slice(Pos.start(arg), Pos.end(arg));
        OrderBuilder b = ctx.order(Rules.LOG_OBJECT_ARGUMENT, m, line, "obj" + ordinal)
                .title("'" + Text.oneLine(src, 40) + "' (" + fq.getClassName() + ") " + why)
                .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
        if (accessor != null && (arg instanceof J.Identifier || arg instanceof J.FieldAccess)) {
            b.edit(Edit.replace(m.file().relPath(), Pos.start(arg), Pos.end(arg), src + "." + accessor))
                    .suggestion("Logs its id (" + src + "." + accessor + ") instead.");
        } else {
            b.suggestion("Log a stable identifier or the few fields that matter, never the object.");
        }
    }

    private void report(MethodInfo m, LogCall lc, Expression arg, int ordinal, String why, String fix) {
        int line = Pos.line(arg);
        if (ctx.ignored(m.file(), line, Rules.LOG_OBJECT_ARGUMENT)) {
            return;
        }
        ctx.order(Rules.LOG_OBJECT_ARGUMENT, m, line, "obj" + ordinal)
                .title("'" + Text.oneLine(arg.printTrimmed(), 50) + "' " + why)
                .suggestion(fix)
                .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
    }

    private boolean entityType(JavaType t) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
        ClassInfo c = fq == null ? null : ctx.a.index.clazz(fq.getFullyQualifiedName());
        return c != null && c.decl().getLeadingAnnotations().stream().anyMatch(a -> ENTITY_ANNOTATIONS.stream().anyMatch(e -> Annotations.is(a, e)));
    }

    /** getId() / id() / getUuid() / uuid(), declared without parameters on the class (or a supertype in the project). */
    private String idAccessor(ClassInfo c) {
        for (ClassInfo k = c; k != null; ) {
            for (Statement s : k.decl().getBody().getStatements()) {
                if (s instanceof J.MethodDeclaration md && md.getParameters().stream().allMatch(p -> p instanceof J.Empty)
                        && Set.of("getId", "id", "getUuid", "uuid").contains(md.getSimpleName())) {
                    return md.getSimpleName() + "()";
                }
            }
            if (k.decl().getKind() == J.ClassDeclaration.Kind.Type.Record) {
                var header = k.decl().getPrimaryConstructor();
                if (header != null) {
                    for (Statement p : header) {
                        if (p instanceof J.VariableDeclarations vd && vd.getVariables().stream().anyMatch(v -> Set.of("id", "uuid").contains(v.getSimpleName()))) {
                            return vd.getVariables().stream().map(J.VariableDeclarations.NamedVariable::getSimpleName)
                                    .filter(n -> Set.of("id", "uuid").contains(n)).findFirst().orElseThrow() + "()";
                        }
                    }
                }
            }
            JavaType.FullyQualified sup = k.type().getSupertype();
            k = sup == null ? null : ctx.a.index.clazz(sup.getFullyQualifiedName());
        }
        return null;
    }

    /** A record of simple values (counts, names, flags), or a class with its own toString(). */
    private boolean valueObject(ClassInfo c) {
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.MethodDeclaration md && md.getSimpleName().equals("toString")
                    && md.getParameters().stream().allMatch(p -> p instanceof J.Empty)) {
                return true;
            }
        }
        if (c.decl().getKind() != J.ClassDeclaration.Kind.Type.Record || c.decl().getPrimaryConstructor() == null) {
            return false;
        }
        for (Statement p : c.decl().getPrimaryConstructor()) {
            if (!(p instanceof J.VariableDeclarations vd)) {
                continue;
            }
            JavaType t = vd.getType();
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
            boolean simple = t instanceof JavaType.Primitive || fq != null && (SIMPLE.contains(fq.getFullyQualifiedName())
                    || fq.getFullyQualifiedName().startsWith("java.time.") || fq.getKind() == JavaType.FullyQualified.Kind.Enum);
            if (!simple || vd.getVariables().stream().anyMatch(v -> ctx.domain.isPiiName(v.getSimpleName()))) {
                return false;
            }
        }
        return true;
    }

    /** List.of(..)/EnumSet/constants: a fixed, known number of elements. */
    private boolean bounded(MethodInfo m, Expression arg) {
        if (arg instanceof J.MethodInvocation mi && Set.of("limit", "subList", "first", "top", "head", "take", "firstN")
                .contains(mi.getSimpleName())) {
            return true;   // already capped
        }
        if (arg.getType() instanceof JavaType.Parameterized p && !p.getTypeParameters().isEmpty()
                && TypeUtils.asFullyQualified(p.getTypeParameters().getFirst()) instanceof JavaType.FullyQualified el
                && el.getKind() == JavaType.FullyQualified.Kind.Enum && TypeUtils.isAssignableTo("java.util.Collection", arg.getType())) {
            return true;   // at most one element per enum constant
        }
        Expression init = null;
        if (arg instanceof J.MethodInvocation mi) {
            init = mi;
        } else if (arg instanceof J.Identifier id) {
            if (id.getFieldType() != null && id.getFieldType().getOwner() instanceof JavaType.FullyQualified
                    && id.getFieldType().hasFlags(org.openrewrite.java.tree.Flag.Static, org.openrewrite.java.tree.Flag.Final)) {
                return true;   // a constant
            }
            init = localInitializer(m, id.getSimpleName());
        }
        return init instanceof J.MethodInvocation f && BOUNDED_FACTORIES.contains(f.getSimpleName())
                && f.getArguments().stream().noneMatch(a -> TypeUtils.isAssignableTo("java.util.Collection", a.getType()));
    }

    private static Expression localInitializer(MethodInfo m, String name) {
        Expression[] found = {null};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, Integer p) {
                if (v.getSimpleName().equals(name) && v.getInitializer() != null) {
                    found[0] = v.getInitializer();
                }
                return super.visitVariable(v, p);
            }
        }.visit(m.decl().getBody(), 0);
        return found[0];
    }

    private void collection(MethodInfo m, LogCall lc, Expression arg, String size, int ordinal) {
        int line = Pos.line(arg);
        if (ctx.ignored(m.file(), line, Rules.LOG_COLLECTION_ARGUMENT)) {
            return;
        }
        String src = m.file().slice(Pos.start(arg), Pos.end(arg));
        OrderBuilder b = ctx.order(Rules.LOG_COLLECTION_ARGUMENT, m, line, "coll" + ordinal)
                .title("'" + Text.oneLine(src, 40) + "' is logged whole: unbounded output, and each element's toString runs "
                        + "(lazy loading, personal data)")
                .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
        boolean simple = arg instanceof J.Identifier || arg instanceof J.FieldAccess || arg instanceof J.MethodInvocation;
        String sized = src + size;
        Expression root = mappedStreamRoot(arg);
        if (root != null) {
            sized = m.file().slice(Pos.start(root), Pos.end(root)) + ".size()";   // lines.stream().map(..).toList() -> lines.size()
        }
        if (simple) {
            b.edit(Edit.replace(m.file().relPath(), Pos.start(arg), Pos.end(arg), sized))
                    .suggestion("Logs its size; log individual ids at DEBUG inside the loop that handles them if needed.");
            // "[couponIds={}]" names the collection: the value is now a count, so is the key
            if (lc.template() instanceof J.Literal t && t.getValue() instanceof String raw) {
                List<String> names = MessageTemplate.placeholderNames(raw);
                String name = argIndex < names.size() ? names.get(argIndex) : null;
                String base = arg instanceof J.Identifier id ? id.getSimpleName() : arg instanceof J.FieldAccess fa ? fa.getSimpleName() : null;
                if (name != null && base != null) {
                    String key = ctx.policy.logging().style().apply(DomainValues.countKey(base));
                    String renamed = raw.replaceFirst("(?<![\\w.\\-])" + Pattern.quote(name) + "=\\{}", Matcher.quoteReplacement(key) + "={}");
                    if (!renamed.equals(raw)) {
                        b.edit(Edit.replace(m.file().relPath(), Pos.start(t), Pos.end(t), Text.javaString(renamed)));
                    }
                }
            }
        } else {
            b.suggestion("Log its size (" + src + size + ") instead.");
        }
    }

    /** {@code xs.stream().map(..).toList()}: same count as {@code xs} (no filter/distinct/limit). */
    private static Expression mappedStreamRoot(Expression e) {
        Set<String> sameCount = Set.of("map", "toList", "collect", "sorted", "peek", "mapToObj", "boxed");
        for (Expression cur = e; cur instanceof J.MethodInvocation mi; cur = mi.getSelect()) {
            if (mi.getSimpleName().equals("stream") && mi.getSelect() != null
                    && TypeUtils.isAssignableTo("java.util.Collection", mi.getSelect().getType())) {
                return cur == e ? null : mi.getSelect();
            }
            if (!sameCount.contains(mi.getSimpleName())) {
                return null;
            }
        }
        return null;
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** Methods that log for their callers: a Logger parameter, or a logging static method of a *Log*Helper class. */
    private void helpers() {
        for (MethodInfo h : ctx.methods()) {
            if (h.decl().getBody() == null || h.facts().logs.isEmpty()) {
                continue;
            }
            boolean loggerParam = h.decl().getParameters().stream().anyMatch(p -> p instanceof J.VariableDeclarations vd
                    && TypeUtils.isOfClassType(vd.getType(), "org.slf4j.Logger"));
            boolean helperClass = HELPER_CLASS.matcher(h.owner().simpleName()).matches() && h.type().hasFlags(org.openrewrite.java.tree.Flag.Static);
            if (!loggerParam && !helperClass) {
                continue;
            }
            String body = h.file().slice(Pos.start(h.decl()), Pos.end(h.decl()));
            if (JUSTIFIED.matcher(h.name() + " " + h.owner().simpleName() + " " + body).find()) {
                continue;   // redaction / masking is a real cross-cutting reason
            }
            List<CallGraph.Edge> callers = ctx.a.graph.in(h).stream().filter(e -> !e.from().owner().fqn().equals(h.owner().fqn())).toList();
            if (callers.isEmpty() || ctx.ignored(h.file(), h.line(), Rules.LOG_HELPER)) {
                continue;
            }
            OrderBuilder b = ctx.order(Rules.LOG_HELPER, h, h.line(), "helper")
                    .title("Log helper " + h.label() + " writes log lines for " + callers.size() + " call site(s): the message, "
                            + "level and logger are invisible where the event happens")
                    .suggestion("Inline it: an explicit log statement at each call site with domain wording, e.g. "
                            + "log.debug(\"Coupon income entities prepared [count={}]\", entities.size()). Keep a helper only "
                            + "for verified cross-cutting behaviour (redaction, alert contract, sink adaptation).");
            callers.stream().limit(10).forEach(e -> b.location(e.from().file().relPath(), e.site().line(), Rules.LOG_HELPER));
        }
    }
}
