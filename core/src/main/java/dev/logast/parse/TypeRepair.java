package dev.logast.parse;

import org.openrewrite.ExecutionContext;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * Gives calls back the method type javac's error recovery took away, so the analysis does not depend on the JDK.
 * <p>When an argument's type cannot be resolved (a library missing from the classpath), JDK 21's javac leaves the call
 * without a usable method type: none at all, or one with no parameters and an unknown return type. JDK 25's javac
 * keeps the declared signature. Every rule and the call graph read these types, so they are repaired once, here:
 * the method is looked up by name and argument count in the receiver's type and its supertypes, narrowed by the
 * argument types that are known, and taken only when exactly one remains (true overloads stay as they are, never
 * guessed). Children are repaired first, so a repaired inner call gives its outer call a known argument type.
 */
final class TypeRepair extends JavaIsoVisitor<ExecutionContext> {

    static J.CompilationUnit repair(J.CompilationUnit cu, ExecutionContext ctx) {
        return (J.CompilationUnit) new TypeRepair().visitNonNull(cu, ctx);
    }

    /** Per method: local {@code var}s whose type came back with their repaired initializer, by name. */
    private final Deque<Map<String, JavaType>> locals = new ArrayDeque<>();

    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
        locals.push(new HashMap<>());
        try {
            return super.visitMethodDeclaration(method, ctx);
        } finally {
            locals.pop();
        }
    }

    @Override
    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
        J.VariableDeclarations vd = super.visitVariableDeclarations(multiVariable, ctx);
        if (locals.isEmpty() || vd.getVariables().size() != 1 || !known(vd.getVariables().getFirst().getInitializer())
                || known(vd.getVariables().getFirst().getName())) {
            return vd;
        }
        // `var x = call(...)` whose call was repaired: x and its later uses get the call's type
        J.VariableDeclarations.NamedVariable v = vd.getVariables().getFirst();
        JavaType t = v.getInitializer().getType();
        locals.peek().put(v.getSimpleName(), t);
        return vd.withVariables(List.of(v.withName(v.getName().withType(t))));
    }

    @Override
    public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
        J.Identifier id = super.visitIdentifier(identifier, ctx);
        if (!known(id) && !locals.isEmpty() && locals.peek().containsKey(id.getSimpleName())) {
            return id.withType(locals.peek().get(id.getSimpleName()));
        }
        return id;
    }

    private static boolean known(Expression e) {
        return e != null && e.getType() != null && !(e.getType() instanceof JavaType.Unknown);
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
        J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);
        JavaType.Method mt = mi.getMethodType();
        if (mt != null && !(mt.getDeclaringType() instanceof JavaType.Unknown) && !broken(mt, mi)) {
            return mi;
        }
        JavaType.Method declared = declared(mi, mt);
        if (declared == null) {
            return mi;
        }
        return mi.withMethodType(declared).withName(mi.getName().withType(declared));
    }

    /** javac's error recovery: no parameters and an unknown return type, for a call that passes arguments. */
    static boolean broken(JavaType.Method mt, J.MethodInvocation mi) {
        return mt.getReturnType() instanceof JavaType.Unknown && mt.getParameterTypes().size() != args(mi).size();
    }

    private JavaType.Method declared(J.MethodInvocation mi, JavaType.Method partial) {
        JavaType.FullyQualified receiver;
        if (partial != null && !(partial.getDeclaringType() instanceof JavaType.Unknown)) {
            receiver = partial.getDeclaringType();   // javac kept where the method lives, only its signature is lost
        } else if (mi.getSelect() == null) {
            J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
            receiver = cd == null ? null : cd.getType();
        } else {
            receiver = TypeUtils.asFullyQualified(mi.getSelect().getType());
        }
        if (receiver == null || receiver instanceof JavaType.Unknown) {
            return null;
        }
        List<Expression> args = args(mi);
        String name = mi.getSimpleName();
        Deque<JavaType.FullyQualified> todo = new ArrayDeque<>(List.of(receiver));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            JavaType.FullyQualified t = todo.poll();
            if (t instanceof JavaType.Unknown || !seen.add(t.getFullyQualifiedName())) {
                continue;
            }
            List<JavaType.Method> named = t.getMethods().stream().filter(m -> m.getName().equals(name)).toList();
            if (!named.isEmpty()) {
                List<JavaType.Method> fitting = named.stream().filter(m -> fits(m, args)).toList();
                return fitting.size() == 1 ? fitting.getFirst() : null;   // overloads here: not ours to guess
            }
            List<JavaType.FullyQualified> next = new ArrayList<>(t.getInterfaces());
            next.addFirst(t.getSupertype());
            if (mi.getSelect() == null) {
                next.add(t.getOwningClass());   // a nested class calling its enclosing class's method
            }
            next.stream().filter(Objects::nonNull).forEach(todo::add);
        }
        return null;
    }

    /** Same number of parameters, and every argument whose type is known fits its parameter. */
    private static boolean fits(JavaType.Method m, List<Expression> args) {
        List<JavaType> params = m.getParameterTypes();
        if (params.size() != args.size()) {
            return false;
        }
        for (int i = 0; i < args.size(); i++) {
            JavaType arg = args.get(i).getType();
            if (arg == null || arg instanceof JavaType.Unknown || arg == JavaType.Primitive.Null) {
                continue;
            }
            JavaType p = params.get(i);
            if (p instanceof JavaType.GenericTypeVariable || p instanceof JavaType.Primitive || arg instanceof JavaType.Primitive) {
                continue;   // generics and boxing: javac knows better than a name check, so do not rule it out
            }
            JavaType.FullyQualified pf = TypeUtils.asFullyQualified(p);
            if (pf != null && !(pf instanceof JavaType.Unknown) && !TypeUtils.isAssignableTo(pf.getFullyQualifiedName(), arg)) {
                return false;
            }
        }
        return true;
    }

    private static List<Expression> args(J.MethodInvocation mi) {
        return mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
    }
}
