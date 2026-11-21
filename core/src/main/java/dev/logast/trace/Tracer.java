package dev.logast.trace;

import dev.logast.graph.CodeIndex;
import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * #217: runs the project's own code on {@link Value}s, as far as it can be known without running it: Spring's beans
 * (bound {@code @ConfigurationProperties}, {@code @Bean} methods, injected fields and {@code List<T>} of every bean of
 * T), locals, fields, getters and setters (Lombok's too, from the fields), constructors (Lombok's by field order),
 * builders, enum constants with their constructor arguments, {@code if} (both branches when the condition is not known),
 * {@code for} over known lists, streams ({@code map}, {@code filter}, {@code forEach}), {@code Optional}, maps and
 * lists (a {@code pollFirst()} takes from the list, as at runtime), lambdas and method references. Library objects keep
 * what is set on them ({@code setX}, fluent builders); calls on a library's {@code Scheduler} are recorded as
 * {@link #events}. What it cannot know is {@link Value.Unknown}; a budget bounds every run.
 */
public final class Tracer {
    private static final int BUDGET = 400_000;
    private static final int DEPTH = 40;
    private static final Set<String> STEREOTYPES = Set.of("Component", "Service", "Repository", "Configuration", "Controller",
            "RestController", "ConfigurationProperties");
    private static final Set<String> LISTS = Set.of("List", "ArrayList", "LinkedList", "Collection", "Set", "LinkedHashSet",
            "HashSet", "Deque", "ArrayDeque", "Queue", "Iterable");
    private static final Set<String> MAPS = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "EnumMap", "JobDataMap",
            "ConcurrentHashMap", "SortedMap");

    /** A call on a library object the caller wants to see (a scheduler's {@code triggerJob}). */
    public record Event(String type, String method, List<Value> args) {
    }

    private final Types types;
    private final CodeIndex index;
    private final Binding binding;
    /** Every object the code made ({@code new}, a builder's {@code build()}, a bound or bean instance), in order. */
    public final List<Value.Obj> created = new ArrayList<>();
    public final List<Event> events = new ArrayList<>();
    private final Map<String, Value.Obj> singletons = new HashMap<>();
    private final Map<String, Value> beans = new HashMap<>();
    private final Map<String, Value.Obj> enums = new HashMap<>();
    private int steps;

    public Tracer(Types types, Map<String, String> config) {
        this.types = types;
        this.index = types.index();
        this.binding = new Binding(types, config);
    }

    public Types types() {
        return types;
    }

    private static final class Frame {
        final Map<String, Value> locals = new HashMap<>();
        final Value.Obj self;
        final ClassInfo cls;
        final int depth;

        Frame(Value.Obj self, ClassInfo cls, int depth) {
            this.self = self;
            this.cls = cls;
            this.depth = depth;
        }
    }

    private static final class Return extends RuntimeException {
        final transient Value value;

        Return(Value value) {
            super(null, null, false, false);
            this.value = value;
        }
    }

    private static final class OutOfBudget extends RuntimeException {
        OutOfBudget() {
            super(null, null, false, false);
        }
    }

    // ---- entry points ---------------------------------------------------------------------------------------------

    public static boolean isBean(ClassInfo c) {
        return c.decl().getLeadingAnnotations().stream().anyMatch(a -> STEREOTYPES.contains(a.getSimpleName()));
    }

    public static boolean annotated(MethodInfo m, String simple) {
        return m.decl().getLeadingAnnotations().stream().anyMatch(a -> a.getSimpleName().equals(simple));
    }

    /** The bean (one per class): bound when it is a {@code @ConfigurationProperties} class. */
    public Value.Obj instance(ClassInfo c) {
        Value.Obj o = singletons.get(c.fqn());
        if (o == null) {
            o = Binding.prefix(c) != null ? binding.bind(c) : new Value.Obj(c.fqn());
            singletons.put(c.fqn(), o);
            created.add(o);
        }
        return o;
    }

    /** A {@code @Bean} method's bean (once: Spring calls it once), its parameters injected. */
    public Value bean(MethodInfo m) {
        Value v = beans.get(m.key());
        if (v != null) {
            return v;
        }
        beans.put(m.key(), new Value.Unknown("bean in creation"));
        List<Value> args = new ArrayList<>();
        for (Statement p : m.decl().getParameters()) {
            if (p instanceof J.VariableDeclarations vd) {
                args.add(inject(vd.getTypeExpression() == null ? "" : vd.getTypeExpression().printTrimmed(), m.owner()));
            }
        }
        v = run(m, m.decl().hasModifier(J.Modifier.Type.Static) ? null : instance(m.owner()), args);
        if (v instanceof Value.Obj o && o.bean == null) {
            o.bean = m.name();
        }
        beans.put(m.key(), v);
        return v;
    }

    /** Runs {@code m} on {@code self} with {@code args}; never throws: what goes wrong is {@link Value.Unknown}. */
    public Value run(MethodInfo m, Value.Obj self, List<Value> args) {
        try {
            return invoke(m, self, args, 0);
        } catch (OutOfBudget e) {
            return new Value.Unknown("too much to follow");
        } catch (RuntimeException e) {
            return new Value.Unknown("could not follow: " + e.getClass().getSimpleName());
        }
    }

    /** The method {@code name} with {@code argc} parameters of {@code c} or its supertypes; null when none. */
    public MethodInfo find(ClassInfo c, String name, int argc) {
        if (c == null) {
            return null;
        }
        java.util.LinkedHashSet<ClassInfo> chain = new java.util.LinkedHashSet<>();
        chain.add(c);
        chain.addAll(index.sourceSupertypes(c));
        ClassInfo sup = c.decl().getExtends() == null ? null : types.resolve(c.decl().getExtends().printTrimmed(), c);
        while (sup != null && chain.add(sup)) {
            sup = sup.decl().getExtends() == null ? null : types.resolve(sup.decl().getExtends().printTrimmed(), sup);
        }
        MethodInfo abstractOne = null;
        for (ClassInfo k : chain) {
            for (MethodInfo m : index.methodsOf(k.fqn())) {
                if (!m.decl().isConstructor() && m.name().equals(name) && params(m).size() == argc) {
                    if (m.decl().getBody() != null) {
                        return m;
                    }
                    abstractOne = abstractOne == null ? m : abstractOne;
                }
            }
        }
        return abstractOne;
    }

    private static List<String> params(MethodInfo m) {
        List<String> out = new ArrayList<>();
        for (Statement p : m.decl().getParameters()) {
            if (p instanceof J.VariableDeclarations vd) {
                vd.getVariables().forEach(v -> out.add(v.getSimpleName()));
            }
        }
        return out;
    }

    // ---- invocation -----------------------------------------------------------------------------------------------

    private Value invoke(MethodInfo m, Value.Obj self, List<Value> args, int depth) {
        if (depth > DEPTH || m.decl().getBody() == null) {
            return new Value.Unknown(m.decl().getBody() == null ? "no body: " + m.name() : "too deep");
        }
        Frame f = new Frame(self, m.owner(), depth + 1);
        List<String> names = params(m);
        for (int i = 0; i < names.size(); i++) {
            f.locals.put(names.get(i), i < args.size() ? args.get(i) : new Value.Unknown("argument"));
        }
        try {
            exec(m.decl().getBody(), f);
        } catch (Return r) {
            return r.value;
        }
        return Value.Lit.NULL;
    }

    /** A call of a method of the project on {@code self} (a bean method of a configuration: its bean). */
    private Value callProject(MethodInfo m, Value.Obj self, List<Value> args, Frame f) {
        if (annotated(m, "Bean") && args.isEmpty()) {
            return bean(m);
        }
        return invoke(m, m.decl().hasModifier(J.Modifier.Type.Static) ? null : self, args, f.depth);
    }

    // ---- statements -----------------------------------------------------------------------------------------------

    private void tick() {
        if (++steps > BUDGET) {
            throw new OutOfBudget();
        }
    }

    private void exec(Statement s, Frame f) {
        tick();
        switch (s) {
            case J.Block b -> {
                for (Statement st : b.getStatements()) {
                    exec(st, f);
                }
            }
            case J.VariableDeclarations vd -> {
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    f.locals.put(v.getSimpleName(), v.getInitializer() == null ? Value.Lit.NULL : eval(v.getInitializer(), f));
                }
            }
            case J.Return r -> throw new Return(r.getExpression() == null ? Value.Lit.NULL : eval(r.getExpression(), f));
            case J.If i -> {
                Boolean c = truth(eval(i.getIfCondition().getTree(), f));
                if (c == null || c) {
                    exec(i.getThenPart(), f);
                }
                if ((c == null || !c) && i.getElsePart() != null) {
                    exec(i.getElsePart().getBody(), f);
                }
            }
            case J.ForEachLoop loop -> {
                Value it = eval(loop.getControl().getIterable(), f);
                if (it instanceof Value.ListV l && loop.getControl().getVariable() instanceof J.VariableDeclarations vd) {
                    String var = vd.getVariables().getFirst().getSimpleName();
                    for (Value item : new ArrayList<>(l.items)) {
                        f.locals.put(var, item);
                        exec(loop.getBody(), f);
                    }
                }
            }
            case J.Try t -> {
                try {
                    exec(t.getBody(), f);
                } finally {
                    if (t.getFinally() != null) {
                        exec(t.getFinally(), f);
                    }
                }
            }
            case J.Throw th -> throw new Return(new Value.Unknown("throws"));
            case J.Label l -> exec(l.getStatement(), f);
            case J.Synchronized sy -> exec(sy.getBody(), f);
            case Expression e -> eval(e, f);
            default -> {
                // loops whose bound is not known, switch: not followed
            }
        }
    }

    // ---- expressions ----------------------------------------------------------------------------------------------

    private Value eval(Expression e, Frame f) {
        tick();
        return switch (e) {
            case J.Literal l -> l.getValue() == null ? Value.Lit.NULL : Value.Lit.of(l.getValue());
            case J.Identifier id -> identifier(id.getSimpleName(), f);
            case J.FieldAccess fa -> fieldAccess(fa, f);
            case J.MethodInvocation mi -> invocation(mi, f);
            case J.NewClass nc -> newObject(nc, f);
            case J.Lambda lambda -> new Value.Fn(lambda, f);
            case J.MemberReference mr -> new Value.Fn(mr, f);
            case J.Parentheses<?> p -> p.getTree() instanceof Expression in ? eval(in, f) : new Value.Unknown("parentheses");
            case J.TypeCast tc -> eval(tc.getExpression(), f);
            case J.Ternary t -> {
                Boolean c = truth(eval(t.getCondition(), f));
                yield c == null ? new Value.Unknown("condition") : eval(c ? t.getTruePart() : t.getFalsePart(), f);
            }
            case J.Binary b -> binary(b, f);
            case J.Unary u -> {
                Value v = eval(u.getExpression(), f);
                yield u.getOperator() == J.Unary.Type.Not && truth(v) != null ? Value.Lit.of(!truth(v)) : new Value.Unknown("unary");
            }
            case J.Assignment as -> {
                Value v = eval(as.getAssignment(), f);
                assign(as.getVariable(), v, f);
                yield v;
            }
            case J.NewArray na -> {
                Value.ListV l = new Value.ListV();
                if (na.getInitializer() != null) {
                    for (Expression x : na.getInitializer()) {
                        if (!(x instanceof J.Empty)) {
                            l.items.add(eval(x, f));
                        }
                    }
                }
                yield l;
            }
            default -> new Value.Unknown(e.getClass().getSimpleName());
        };
    }

    private static Boolean truth(Value v) {
        return v instanceof Value.Lit l && l.value() instanceof Boolean b ? b : null;
    }

    private Value binary(J.Binary b, Frame f) {
        Value l = eval(b.getLeft(), f);
        if (b.getOperator() == J.Binary.Type.And && Boolean.FALSE.equals(truth(l))) {
            return Value.Lit.of(false);
        }
        if (b.getOperator() == J.Binary.Type.Or && Boolean.TRUE.equals(truth(l))) {
            return Value.Lit.of(true);
        }
        Value r = eval(b.getRight(), f);
        switch (b.getOperator()) {
            case And, Or -> {
                Boolean x = truth(l);
                Boolean y = truth(r);
                if (x != null && y != null) {
                    return Value.Lit.of(b.getOperator() == J.Binary.Type.And ? x && y : x || y);
                }
                if (b.getOperator() == J.Binary.Type.And && Boolean.FALSE.equals(y) || b.getOperator() == J.Binary.Type.Or && Boolean.TRUE.equals(y)) {
                    return y.equals(Boolean.TRUE) ? Value.Lit.of(true) : Value.Lit.of(false);
                }
                return new Value.Unknown("condition");
            }
            case Equal, NotEqual -> {
                Boolean same = equal(l, r);
                return same == null ? new Value.Unknown("comparison") : Value.Lit.of(b.getOperator() == J.Binary.Type.Equal == same);
            }
            case Addition -> {
                if (l instanceof Value.Lit x && r instanceof Value.Lit y && (x.value() instanceof String || y.value() instanceof String)) {
                    return Value.Lit.of(x.text() + y.text());
                }
                if (l instanceof Value.Lit x && r instanceof Value.Lit y && x.value() instanceof Number n && y.value() instanceof Number m) {
                    return Value.Lit.of(n.longValue() + m.longValue());
                }
                return new Value.Unknown("arithmetic");
            }
            default -> {
                return new Value.Unknown("operator");
            }
        }
    }

    /** Equal when both are known; null when not known. */
    private static Boolean equal(Value a, Value b) {
        if (a instanceof Value.Unknown || b instanceof Value.Unknown) {
            return null;
        }
        if (a instanceof Value.Lit x && b instanceof Value.Lit y) {
            if (x.value() instanceof Number n && y.value() instanceof Number m) {
                return n.doubleValue() == m.doubleValue();
            }
            return java.util.Objects.equals(x.value() == null ? null : String.valueOf(x.value()), y.value() == null ? null : String.valueOf(y.value()));
        }
        if (a == Value.Lit.NULL || b == Value.Lit.NULL || a instanceof Value.Lit la && la.value() == null || b instanceof Value.Lit lb && lb.value() == null) {
            return false;
        }
        return Value.same(a, b);
    }

    private void assign(Expression target, Value v, Frame f) {
        if (target instanceof J.Identifier id) {
            if (f.locals.containsKey(id.getSimpleName()) || f.self == null) {
                f.locals.put(id.getSimpleName(), v);
            } else {
                f.self.fields.put(id.getSimpleName(), v);
            }
        } else if (target instanceof J.FieldAccess fa) {
            Value o = fa.getTarget() instanceof J.Identifier t && t.getSimpleName().equals("this") ? f.self : eval(fa.getTarget(), f);
            if (o instanceof Value.Obj obj) {
                obj.fields.put(fa.getSimpleName(), v);
            }
        }
    }

    private Value identifier(String name, Frame f) {
        if (f.locals.containsKey(name)) {
            return f.locals.get(name);
        }
        if (name.equals("this")) {
            return f.self == null ? new Value.Unknown("this") : f.self;
        }
        if (f.self != null) {
            ClassInfo c = index.clazz(f.self.type);
            if (f.self.fields.containsKey(name) || c != null && types.field(c, name) != null) {
                return readField(f.self, name);
            }
        }
        if (f.cls != null) {
            Types.Field sf = staticField(f.cls, name);
            if (sf != null) {
                return sf.var().getInitializer() == null ? new Value.Unknown("static field") : eval(sf.var().getInitializer(), new Frame(null, sf.owner(), f.depth));
            }
            if (types.isEnum(f.cls) && enumConstant(f.cls, name) != null && name.equals(enumConstant(f.cls, name))) {
                return new Value.EnumConst(f.cls.fqn(), name);
            }
        }
        Value.EnumConst ec = staticEnum(name, f);
        return ec != null ? ec : new Value.Unknown("name " + name);
    }

    /** An enum constant named alone (a static import): of the only project enum that has it, or one the file imports. */
    private Value.EnumConst staticEnum(String name, Frame f) {
        if (name.isEmpty() || !Character.isUpperCase(name.charAt(0))) {
            return null;
        }
        List<ClassInfo> having = new ArrayList<>();
        for (ClassInfo c : index.classes()) {
            if (types.isEnum(c) && name.equals(enumConstant(c, name))) {
                having.add(c);
            }
        }
        if (having.size() == 1) {
            return new Value.EnumConst(having.getFirst().fqn(), name);
        }
        if (f.cls != null) {
            String text = f.cls.file().text();
            for (ClassInfo c : having) {
                if (text.contains("import static " + c.fqn().replace('$', '.') + ".")) {
                    return new Value.EnumConst(c.fqn(), name);
                }
            }
        }
        return null;
    }

    private Types.Field staticField(ClassInfo c, String name) {
        for (ClassInfo k = c; k != null; ) {
            for (Types.Field fd : types.declared(k)) {
                if (fd.isStatic() && fd.name().equals(name)) {
                    return fd;
                }
            }
            String outer = k.fqn().contains("$") ? k.fqn().substring(0, k.fqn().lastIndexOf('$')) : null;
            k = outer == null ? null : index.clazz(outer);
        }
        return null;
    }

    /** A field of an object: as set, else its initializer, else (a bean's) what Spring injects. */
    private Value readField(Value.Obj o, String name) {
        Value v = o.fields.get(name);
        if (v != null) {
            return v;
        }
        ClassInfo c = index.clazz(o.type);
        Types.Field fd = c == null ? null : types.field(c, name);
        if (fd == null) {
            return new Value.Unknown("field " + name);
        }
        if (fd.var().getInitializer() != null) {
            v = eval(fd.var().getInitializer(), new Frame(o, fd.owner(), 0));
        } else if (isBean(c) || isBean(fd.owner())) {
            v = inject(fd.type(), fd.owner());
        } else {
            return new Value.Unknown("field " + name + " not set");
        }
        o.fields.put(name, v);
        return v;
    }

    /** What Spring injects for a type written in {@code ctx}: every bean of T for a list, else the one bean of T. */
    private Value inject(String type, ClassInfo ctx) {
        String raw = Types.raw(type);
        if (LISTS.contains(raw)) {
            Value.ListV l = new Value.ListV();
            String el = Types.args(type).isEmpty() ? "" : Types.args(type).getFirst();
            ClassInfo t = types.resolve(el, ctx);
            if (t != null) {
                for (ClassInfo c : index.classes()) {
                    if (isBean(c) && !c.isInterface() && !c.decl().hasModifier(J.Modifier.Type.Abstract) && types.isA(c, t)) {
                        l.items.add(instance(c));
                    }
                }
            }
            for (MethodInfo m : beanMethods(raw(el))) {
                l.items.add(bean(m));
            }
            return l;
        }
        List<MethodInfo> made = beanMethods(raw);
        if (!made.isEmpty()) {
            return bean(made.getFirst());
        }
        ClassInfo t = types.resolve(type, ctx);
        if (t != null) {
            for (ClassInfo c : index.classes()) {
                if (isBean(c) && !c.isInterface() && !c.decl().hasModifier(J.Modifier.Type.Abstract) && types.isA(c, t)) {
                    return instance(c);
                }
            }
        }
        return new Value.Unknown("bean " + raw);
    }

    private static String raw(String type) {
        return type.isEmpty() ? "" : Types.raw(type);
    }

    /** The {@code @Bean} methods of the project's configurations whose type (as written) is {@code raw}. */
    private List<MethodInfo> beanMethods(String raw) {
        List<MethodInfo> out = new ArrayList<>();
        for (MethodInfo m : index.methods()) {
            if (annotated(m, "Bean") && m.decl().getReturnTypeExpression() != null
                    && Types.raw(m.decl().getReturnTypeExpression().printTrimmed()).equals(raw)) {
                out.add(m);
            }
        }
        return out;
    }

    private Value fieldAccess(J.FieldAccess fa, Frame f) {
        String name = fa.getSimpleName();
        if (name.equals("class")) {
            ClassInfo c = types.resolve(fa.getTarget().printTrimmed(), f.cls);
            return new Value.ClassRef(c != null ? c.fqn() : fa.getTarget().printTrimmed());
        }
        if (fa.getTarget() instanceof J.Identifier t && t.getSimpleName().equals("this") && f.self != null) {
            return readField(f.self, name);
        }
        String constant = dev.logast.graph.Constants.concatenated(index, fa);
        if (constant != null) {
            return Value.Lit.of(constant);
        }
        if (!(fa.getTarget() instanceof J.Identifier ti && f.locals.containsKey(ti.getSimpleName()))) {
            ClassInfo c = types.resolve(fa.getTarget().printTrimmed(), f.cls);
            if (c != null && !(fa.getTarget() instanceof J.Identifier tid && f.self != null && f.self.fields.containsKey(tid.getSimpleName()))) {
                if (types.isEnum(c) && name.equals(enumConstant(c, name))) {
                    return new Value.EnumConst(c.fqn(), name);
                }
                Types.Field sf = staticField(c, name);
                if (sf != null && sf.var().getInitializer() != null) {
                    return eval(sf.var().getInitializer(), new Frame(null, c, f.depth));
                }
            }
        }
        Value target = eval(fa.getTarget(), f);
        return target instanceof Value.Obj o ? readField(o, name) : new Value.Unknown("field " + name);
    }

    private List<Value> args(List<Expression> args, Frame f) {
        List<Value> out = new ArrayList<>();
        for (Expression a : args) {
            if (!(a instanceof J.Empty)) {
                out.add(eval(a, f));
            }
        }
        return out;
    }

    private Value invocation(J.MethodInvocation mi, Frame f) {
        String name = mi.getSimpleName();
        Expression sel = mi.getSelect();
        if (sel == null) {
            List<Value> a = args(mi.getArguments(), f);
            ClassInfo c = f.self != null && index.clazz(f.self.type) != null ? index.clazz(f.self.type) : f.cls;
            MethodInfo m = find(c, name, a.size());
            if (m == null && f.cls != null && c != f.cls) {
                m = find(f.cls, name, a.size());
            }
            if (m == null && name.equals("builder") && a.isEmpty() && f.cls != null) {   // Lombok's, inside its class
                return new Value.Obj(Library.BUILDER + f.cls.fqn());
            }
            return m == null ? new Value.Unknown("method " + name) : callProject(m, f.self, a, f);
        }
        boolean local = sel instanceof J.Identifier id && (f.locals.containsKey(id.getSimpleName()) || id.getSimpleName().equals("this")
                || f.self != null && (f.self.fields.containsKey(id.getSimpleName())
                || index.clazz(f.self.type) != null && types.field(index.clazz(f.self.type), id.getSimpleName()) != null));
        if (!local && (sel instanceof J.Identifier || sel instanceof J.FieldAccess)) {
            String text = sel.printTrimmed();
            String last = text.substring(text.lastIndexOf('.') + 1);
            if (!last.isEmpty() && Character.isUpperCase(last.charAt(0)) && !last.equals(last.toUpperCase(Locale.ROOT))) {
                ClassInfo c = types.resolve(text, f.cls);
                List<Value> a = args(mi.getArguments(), f);
                return c != null ? staticCall(c, name, a, f) : Library.statics(this, last, name, a);
            }
        }
        Value recv = eval(sel, f);
        return call(recv, name, args(mi.getArguments(), f), f);
    }

    private Value staticCall(ClassInfo c, String name, List<Value> a, Frame f) {
        MethodInfo m = find(c, name, a.size());
        if (m != null) {
            return invoke(m, null, a, f.depth);
        }
        if (name.equals("builder") && a.isEmpty()) {
            Value.Obj b = new Value.Obj(Library.BUILDER + c.fqn());
            return b;
        }
        if (types.isEnum(c) && name.equals("valueOf") && a.size() == 1 && a.getFirst() instanceof Value.Lit l && l.text() != null) {
            String n = enumConstant(c, l.text());
            return n == null ? new Value.Unknown("no constant " + l.text()) : new Value.EnumConst(c.fqn(), n);
        }
        if (types.isEnum(c) && name.equals("values") && a.isEmpty()) {
            Value.ListV l = new Value.ListV();
            enumConstants(c).forEach(n -> l.items.add(new Value.EnumConst(c.fqn(), n)));
            return l;
        }
        return new Value.Unknown("static " + c.simpleName() + "." + name);
    }

    /** A call on a value. */
    Value call(Value recv, String name, List<Value> a, Frame f) {
        tick();
        return switch (recv) {
            case Value.Unknown u -> u;
            case Value.Fn fn -> Set.of("apply", "accept", "get", "test", "run", "call", "applyAsInt").contains(name) ? apply(fn, a) : new Value.Unknown("function " + name);
            case Value.Opt o -> Library.optional(this, o, name, a);
            case Value.ListV l -> Library.list(this, l, name, a);
            case Value.MapV m -> Library.map(m, name, a);
            case Value.Lit l -> Library.literal(l, name, a);
            case Value.ClassRef c -> name.equals("getSimpleName") ? Value.Lit.of(c.fqn().substring(Math.max(c.fqn().lastIndexOf('.'), c.fqn().lastIndexOf('$')) + 1))
                    : name.equals("getName") ? Value.Lit.of(c.fqn()) : new Value.Unknown("class " + name);
            case Value.EnumConst e -> switch (name) {
                case "name", "toString" -> Value.Lit.of(e.name());
                case "equals" -> a.size() == 1 && equal(e, a.getFirst()) != null ? Value.Lit.of(equal(e, a.getFirst())) : new Value.Unknown("equals");
                default -> {
                    Value.Obj o = enumObject(e);
                    yield o == null ? new Value.Unknown("enum " + name) : call(o, name, a, f);
                }
            };
            case Value.Obj o -> {
                ClassInfo c = index.clazz(o.type);
                if (c == null) {
                    yield Library.object(this, o, name, a);
                }
                MethodInfo m = find(c, name, a.size());
                if (m != null && m.decl().getBody() != null) {
                    yield callProject(m, o, a, f);
                }
                String prop = property(name);
                if (a.isEmpty() && prop != null && (o.fields.containsKey(prop) || types.field(c, prop) != null)) {
                    yield readField(o, prop);
                }
                if (a.isEmpty() && (o.fields.containsKey(name) || types.field(c, name) != null)) {   // a record's accessor
                    yield readField(o, name);
                }
                if (a.size() == 1 && name.startsWith("set") && name.length() > 3) {
                    o.fields.put(decap(name.substring(3)), a.getFirst());
                    yield Value.Lit.NULL;
                }
                yield new Value.Unknown("method " + name);
            }
        };
    }

    /** {@code getX} → {@code x}, {@code isX} → {@code x}; null otherwise. */
    static String property(String method) {
        if (method.startsWith("get") && method.length() > 3 && Character.isUpperCase(method.charAt(3))) {
            return decap(method.substring(3));
        }
        if (method.startsWith("is") && method.length() > 2 && Character.isUpperCase(method.charAt(2))) {
            return decap(method.substring(2));
        }
        return null;
    }

    static String decap(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** Calls a lambda or method reference. */
    Value apply(Value.Fn fn, List<Value> a) {
        Frame closure = (Frame) fn.closure();
        if (fn.tree() instanceof J.Lambda l) {
            Frame f = new Frame(closure.self, closure.cls, closure.depth + 1);
            f.locals.putAll(closure.locals);
            List<String> names = new ArrayList<>();
            for (J p : l.getParameters().getParameters()) {
                if (p instanceof J.VariableDeclarations vd) {
                    names.add(vd.getVariables().getFirst().getSimpleName());
                } else if (p instanceof J.Identifier id) {
                    names.add(id.getSimpleName());
                }
            }
            for (int i = 0; i < names.size(); i++) {
                f.locals.put(names.get(i), i < a.size() ? a.get(i) : new Value.Unknown("argument"));
            }
            if (l.getBody() instanceof J.Block b) {
                try {
                    exec(b, f);
                } catch (Return r) {
                    return r.value;
                }
                return Value.Lit.NULL;
            }
            return l.getBody() instanceof Expression e ? eval(e, f) : new Value.Unknown("lambda");
        }
        if (fn.tree() instanceof J.MemberReference mr) {
            String ref = mr.getReference().getSimpleName();
            Expression on = mr.getContaining();
            if (on instanceof J.Identifier id && id.getSimpleName().equals("this")) {
                return call(closure.self == null ? new Value.Unknown("this") : closure.self, ref, a, closure);
            }
            String text = on.printTrimmed();
            boolean local = on instanceof J.Identifier id && closure.locals.containsKey(id.getSimpleName());
            if (!local) {
                ClassInfo c = types.resolve(text, closure.cls);
                if (ref.equals("new")) {
                    return c == null ? Library.construct(this, Types.raw(text), a) : construct(c, a, closure);
                }
                if (c != null) {
                    MethodInfo st = find(c, ref, a.size());
                    if (st != null && st.decl().hasModifier(J.Modifier.Type.Static)) {
                        return invoke(st, null, a, closure.depth);
                    }
                }
                if (Library.isStaticUtility(Types.raw(text))) {
                    return Library.statics(this, Types.raw(text), ref, a);
                }
                return a.isEmpty() ? new Value.Unknown("reference " + ref) : call(a.getFirst(), ref, a.subList(1, a.size()), closure);
            }
            return call(eval(on, closure), ref, a, closure);
        }
        return new Value.Unknown("function");
    }

    // ---- objects --------------------------------------------------------------------------------------------------

    private Value newObject(J.NewClass nc, Frame f) {
        List<Value> a = args(nc.getArguments(), f);
        if (nc.getClazz() == null || nc.getBody() != null) {
            return new Value.Unknown("anonymous class");
        }
        String type = nc.getClazz().printTrimmed();
        ClassInfo c = types.resolve(type, f.cls);
        return c == null ? Library.construct(this, Types.raw(type), a) : construct(c, a, f);
    }

    /** A project object: its constructor run, else (Lombok's) the arguments set to its fields in order. */
    private Value construct(ClassInfo c, List<Value> a, Frame f) {
        Value.Obj o = new Value.Obj(c.fqn());
        created.add(o);
        for (MethodInfo m : index.methodsOf(c.fqn())) {
            if (m.decl().isConstructor() && params(m).size() == a.size()) {
                invoke(m, o, a, f.depth);
                return o;
            }
        }
        assignInOrder(c, o, a);
        return o;
    }

    /** Lombok's all-args or required-args constructor: the arguments to the fields in declaration order. */
    private void assignInOrder(ClassInfo c, Value.Obj o, List<Value> a) {
        List<Types.Field> own = types.declared(c).stream().filter(x -> !x.isStatic()).toList();
        List<Types.Field> required = own.stream().filter(x -> x.var().getInitializer() == null && (x.isFinal() || x.annotated("NonNull"))).toList();
        List<Types.Field> uninitialized = own.stream().filter(x -> x.var().getInitializer() == null).toList();
        List<Types.Field> to = required.size() == a.size() ? required : uninitialized.size() == a.size() ? uninitialized
                : own.size() == a.size() ? own : List.of();
        for (int i = 0; i < to.size(); i++) {
            o.fields.put(to.get(i).name(), a.get(i));
        }
    }

    /** What a builder of {@code fqn} builds: the fields set on it, the others from their initializers (Lombok's @Default). */
    Value.Obj build(String fqn, Value.Obj builder) {
        Value.Obj o = new Value.Obj(fqn);
        created.add(o);
        o.fields.putAll(builder.fields);
        ClassInfo c = index.clazz(fqn);
        if (c != null) {
            for (Types.Field fd : types.fields(c)) {
                if (!fd.isStatic() && !o.fields.containsKey(fd.name()) && fd.var().getInitializer() != null) {
                    o.fields.put(fd.name(), eval(fd.var().getInitializer(), new Frame(o, fd.owner(), 0)));
                }
            }
        }
        return o;
    }

    void record(String type, String method, List<Value> a) {
        events.add(new Event(type, method, a));
    }

    void made(Value.Obj o) {
        created.add(o);
    }

    // ---- enums ----------------------------------------------------------------------------------------------------

    /** The names of an enum's constants, in order. */
    static List<String> enumConstants(ClassInfo c) {
        List<String> out = new ArrayList<>();
        if (c.decl().getBody() == null) {
            return out;
        }
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.EnumValueSet set) {
                set.getEnums().forEach(v -> out.add(v.getName().getSimpleName()));
            }
        }
        return out;
    }

    /** The constant of {@code c} that {@code v} names, as Spring converts it (case and separators relaxed); null if none. */
    public static String enumConstant(ClassInfo c, String v) {
        for (String n : enumConstants(c)) {
            if (n.equals(v)) {
                return n;
            }
        }
        for (String n : enumConstants(c)) {
            if (Binding.canonical(n).equals(Binding.canonical(v))) {
                return n;
            }
        }
        return null;
    }

    /** An enum constant as an object: its fields from its constructor arguments. */
    private Value.Obj enumObject(Value.EnumConst e) {
        String key = e.type() + "#" + e.name();
        if (enums.containsKey(key)) {
            return enums.get(key);
        }
        ClassInfo c = index.clazz(e.type());
        if (c == null || c.decl().getBody() == null) {
            return null;
        }
        Value.Obj o = new Value.Obj(c.fqn());
        enums.put(key, o);
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.EnumValueSet set) {
                for (J.EnumValue v : set.getEnums()) {
                    if (v.getName().getSimpleName().equals(e.name())) {
                        Frame f = new Frame(null, c, 0);
                        List<Value> a = v.getInitializer() == null ? List.of() : args(v.getInitializer().getArguments(), f);
                        boolean ran = false;
                        for (MethodInfo m : index.methodsOf(c.fqn())) {
                            if (m.decl().isConstructor() && params(m).size() == a.size()) {
                                invoke(m, o, a, 0);
                                ran = true;
                                break;
                            }
                        }
                        if (!ran) {
                            assignInOrder(c, o, a);
                        }
                    }
                }
            }
        }
        return o;
    }

    Value.Obj enumObjectOf(Value.EnumConst e) {
        return enumObject(e);
    }
}
