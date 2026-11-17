package dev.logast.rules;

import dev.logast.model.CatchInfo;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;

/**
 * CAUSE_DROPPED (errors pack): {@code catch (IOException e) { throw new ExportException(e.getMessage()); }} throws away
 * the original stack trace; whoever logs the new exception sees where it was rethrown, not what failed. AUTO when the
 * thrown type has a constructor taking the same arguments plus the cause ({@code e} is appended); report otherwise.
 */
final class CauseRules {
    private final RuleContext ctx;

    CauseRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.CAUSE_DROPPED)) {
            return;
        }
        for (MethodInfo m : ctx.methods()) {
            for (CatchInfo c : m.facts().catches) {
                for (J.Throw t : c.throwsList) {
                    if (t.getException() instanceof J.NewClass nc && nc.getBody() == null && !passesCause(nc)) {
                        dropped(m, c, nc);
                    } else if (t.getException() instanceof J.MethodInvocation mi && !passesCause(mi)) {
                        droppedByFactory(m, c, mi);
                    }
                }
            }
        }
    }

    /** One of the arguments is a Throwable (the caught exception, or one wrapping it). */
    private static boolean passesCause(J.NewClass nc) {
        return args(nc).stream().anyMatch(a -> TypeUtils.isAssignableTo("java.lang.Throwable", a.getType()));
    }

    /**
     * {@code throw Errors.failed(id)}: a factory that gets no Throwable (nor is called on one, as {@code e.getCause()})
     * cannot attach the caught exception. Report only: the factory needs an overload that takes the cause.
     */
    private static boolean passesCause(J.MethodInvocation mi) {
        return mi.getArguments().stream().anyMatch(a -> TypeUtils.isAssignableTo("java.lang.Throwable", a.getType()))
                || mi.getSelect() != null && TypeUtils.isAssignableTo("java.lang.Throwable", mi.getSelect().getType())
                || mi.getMethodType() == null;
    }

    private void droppedByFactory(MethodInfo m, CatchInfo c, J.MethodInvocation mi) {
        int line = Pos.line(mi);
        if (ctx.ignored(m.file(), line, Rules.CAUSE_DROPPED)) {
            return;
        }
        String call = Text.oneLine(mi.printTrimmed(), 120);
        OrderBuilder b = ctx.orderNth(Rules.CAUSE_DROPPED, m, line, "cause")
                .title("catch (" + c.typeNames() + " " + c.param + ") throws " + call + " without " + c.param
                        + " as its cause: the original stack trace is lost");
        b.suggestion(c.param.equals("_") ? "Name the caught exception (catch (" + c.typeNames() + " e)) and pass it as the cause."
                : "Pass " + c.param + " as the cause: give " + mi.getSimpleName() + " a (…, Throwable cause) overload, or call "
                + "initCause(" + c.param + ") on what it returns before throwing.");
    }

    private void dropped(MethodInfo m, CatchInfo c, J.NewClass nc) {
        SourceFileInfo f = m.file();
        int line = Pos.line(nc);
        if (ctx.ignored(f, line, Rules.CAUSE_DROPPED) || nc.getClazz() == null) {
            return;
        }
        String type = nc.getClazz().printTrimmed();
        OrderBuilder b = ctx.orderNth(Rules.CAUSE_DROPPED, m, line, "cause")
                .title("catch (" + c.typeNames() + " " + c.param + ") throws a new " + type + " without " + c.param
                        + " as its cause: the original stack trace is lost");
        List<Expression> args = args(nc);
        if (c.param.equals("_")) {
            // an unnamed catch parameter (Java 22+) cannot be passed on: it has to get a name first
            b.suggestion("Name the caught exception (catch (" + c.typeNames() + " e)) and pass it as the cause.");
            return;
        }
        if (!hasCauseConstructor(nc, args, c)) {
            b.suggestion("Pass " + c.param + " as the cause: add a constructor (…, Throwable cause) to " + type
                    + ", or call initCause(" + c.param + ") before throwing.");
            return;
        }
        // the closing parenthesis of the constructor call: append the cause before it
        String text = f.text();
        int close = text.lastIndexOf(')', Pos.end(nc) - 1);
        if (close < Pos.start(nc)) {
            return;
        }
        b.edit(Edit.insert(f.relPath(), close, (args.isEmpty() ? "" : ", ") + c.param));
        b.suggestion("Appends " + c.param + " as the cause (" + type + " has a constructor that takes it).");
    }

    /** A constructor with the same arguments plus one more parameter that the caught exception fits. */
    private static boolean hasCauseConstructor(J.NewClass nc, List<Expression> args, CatchInfo c) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(nc.getType());
        if (type == null || type instanceof JavaType.Unknown) {
            return false;
        }
        for (JavaType.Method ctor : type.getMethods()) {
            List<JavaType> params = ctor.getParameterTypes();
            if (!ctor.getName().equals("<constructor>") || params.size() != args.size() + 1) {
                continue;
            }
            boolean fits = true;
            for (int i = 0; i < args.size() && fits; i++) {
                JavaType arg = args.get(i).getType();
                JavaType.FullyQualified p = TypeUtils.asFullyQualified(params.get(i));
                fits = arg == null || p == null || TypeUtils.isAssignableTo(p.getFullyQualifiedName(), arg)
                        || params.get(i) instanceof JavaType.Primitive;
            }
            JavaType.FullyQualified last = TypeUtils.asFullyQualified(params.getLast());
            boolean causeFits = last != null && c.types.stream().allMatch(t -> TypeUtils.isAssignableTo(last.getFullyQualifiedName(), t));
            if (fits && causeFits) {
                return true;
            }
        }
        return false;
    }

    private static List<Expression> args(J.NewClass nc) {
        return nc.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
    }
}
