package dev.logast.rules;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Flag;

import java.util.*;

/**
 * REACTIVE_CONTEXT_LOSS (logging and tracing packs, #14): an entrypoint that returns a Mono/Flux (a WebFlux controller
 * or router handler, a reactive listener) runs its pipeline on whatever thread emits, so the current observation, and
 * with it the trace and span ids Micrometer Tracing puts into MDC, is gone in the operators unless Reactor restores
 * ThreadLocals in every operator.
 *
 * <p>Sources: Reactor reference › Context-Propagation Support: "the automatic mode, enabled via
 * Hooks.enableAutomaticContextPropagation() [...] provides access to ThreadLocal values throughout the reactive chain";
 * the default (limited) mode restores them only in {@code handle} and {@code tap}; both need
 * {@code io.micrometer:context-propagation} next to reactor-core. Spring Boot reference › Observability › Context
 * Propagation: "By default, ThreadLocal values are not automatically reinstated in reactive operators. This behavior is
 * controlled with the spring.reactor.context-propagation property, which can be set to auto" (3.5 and 4.1). The
 * property exists since Boot 3.2 (application properties appendix; ReactorAutoConfiguration calls the hook for
 * {@code auto}); Boot 4 moved that auto-configuration to the spring-boot-reactor module, which
 * spring-boot-starter-webflux brings and a service with only spring-webflux (WebClient) does not. Where the property
 * has no effect (Boot 3.0/3.1, or Boot 4 without the module) the hook goes first into {@code main}: it "applies only to
 * new subscriptions, so it is recommended to enable this hook when the application starts".
 */
final class ReactiveRules {
    static final String PROPERTY = "spring.reactor.context-propagation";
    /** Boot 4 (spring-boot-reactor) and Boot 3.2+ (spring-boot-autoconfigure): the property is read. */
    private static final List<String> REACTOR_AUTO_CONFIGURATION = List.of(
            "org.springframework.boot.reactor.autoconfigure.ReactorAutoConfiguration",
            "org.springframework.boot.autoconfigure.reactor.ReactorAutoConfiguration");
    private static final String CONTEXT_REGISTRY = "io.micrometer.context.ContextRegistry";
    private static final String HOOK = "enableAutomaticContextPropagation";

    private final RuleContext ctx;

    ReactiveRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.REACTIVE_CONTEXT_LOSS) || ctx.a.appPackage == null) {
            return;
        }
        List<Entrypoint> reactive = ctx.a.entrypoints.stream()
                .filter(ep -> !ep.method().owner().fqn().startsWith(ctx.a.supportPackage + ".") && ctx.reactive(ep.method()))
                .toList();
        if (reactive.isEmpty()) {
            return;
        }
        var probe = ctx.a.project.probe();
        boolean library = probe.hasClass(CONTEXT_REGISTRY);
        boolean bootProperty = REACTOR_AUTO_CONFIGURATION.stream().anyMatch(probe::hasClass);
        String value = ctx.a.springProperties.get(PROPERTY);
        boolean propertyOn = value != null && value.strip().equalsIgnoreCase("auto");
        boolean hooked = ctx.a.index.classes().stream().map(ClassInfo::file).distinct()
                .anyMatch(f -> f.text().contains(HOOK + "("));
        boolean on = hooked || bootProperty && propertyOn;
        if (on && library) {
            return;
        }
        MethodInfo main = bootProperty ? null : main();
        String at = bootProperty || main == null ? ServiceYaml.path(ctx) : main.file().relPath();
        OrderBuilder b = ctx.order(Rules.REACTIVE_CONTEXT_LOSS, "reactor-context").at(at, bootProperty || main == null ? 1 : main.line())
                .title(reactive.size() + " entrypoint" + (reactive.size() == 1 ? " returns" : "s return") + " a Mono/Flux, but "
                        + (on ? "io.micrometer:context-propagation is not on the classpath"
                        : "Reactor does not restore the trace in its operators" + (library ? "" : " (and io.micrometer:context-propagation "
                        + "is not on the classpath)"))
                        + ": log lines inside the pipeline carry no trace id, and spans started there lose their parent")
                .doneWhen((bootProperty ? PROPERTY + ": auto in the service's configuration"
                        : "Hooks.enableAutomaticContextPropagation() runs at startup (first in main)")
                        + ", and io.micrometer:context-propagation is on the classpath (Micrometer Tracing brings it)")
                .basis((bootProperty ? PROPERTY + " is " + (value == null ? "not set (default: limited)" : "'" + value + "'")
                        : "Spring Boot's ReactorAutoConfiguration is not on the classpath (Boot 3.0/3.1, or Boot 4 without "
                        + "spring-boot-reactor), so " + PROPERTY + " would have no effect")
                        + "; io.micrometer:context-propagation " + (library ? "is" : "is not") + " on the classpath");
        for (Entrypoint ep : reactive) {
            MethodInfo m = ep.method();
            if (!ctx.ignored(m.file(), m.line(), Rules.REACTIVE_CONTEXT_LOSS)) {
                b.location(m.file().relPath(), m.line(), Rules.REACTIVE_CONTEXT_LOSS);
            }
        }
        String libraryNote = library ? "" : " Add io.micrometer:context-propagation (it comes with micrometer-tracing; the "
                + "Micrometer BOM in Boot 4 manages its version), then run `logast classpath .`: without it Reactor restores nothing.";
        if (on) {
            b.suggestion("Automatic context propagation is switched on." + libraryNote);
            return;
        }
        if (bootProperty) {
            if (value != null) {
                // set to limited on purpose: a decision, not ours to overturn
                b.suggestion("Set " + PROPERTY + ": auto (it is '" + value + "'): Reactor then restores the observation, and so the "
                        + "trace ids in MDC, in every operator." + libraryNote);
                return;
            }
            ServiceYaml.merge(ctx, b, "spring:\n  reactor:\n    context-propagation: auto\n");
            b.suggestion("Sets " + PROPERTY + ": auto: Reactor restores the observation, and so the trace ids in MDC, in every "
                    + "operator of every new subscription." + libraryNote);
            return;
        }
        int[] inner = main == null || main.decl().getBody() == null ? null : JavaEdits.blockInner(main.file().text(), main.decl().getBody());
        if (inner == null) {
            b.suggestion("Call Hooks.enableAutomaticContextPropagation() first thing at startup (" + PROPERTY + ": auto needs Spring "
                    + "Boot 3.2+ and, in Boot 4, spring-boot-reactor)." + libraryNote);
            return;
        }
        SourceFileInfo f = main.file();
        String text = f.text();
        String indent = Text.indentAt(text, Pos.start(main.decl().getBody())) + Text.indentUnit(text);
        b.edit(Edit.insert(f.relPath(), inner[0], indent + "Hooks." + HOOK + "();\n"));
        b.edits(JavaEdits.imports(f, "reactor.core.publisher.Hooks"));
        b.suggestion("Calls Hooks.enableAutomaticContextPropagation() first in main (before any subscription): Reactor restores "
                + "the observation, and so the trace ids in MDC, in every operator. With spring-boot-reactor on the classpath "
                + "(spring-boot-starter-webflux brings it), " + PROPERTY + ": auto does the same." + libraryNote);
    }

    /** {@code main} of the {@code @SpringBootApplication} class. */
    private MethodInfo main() {
        for (MethodInfo m : ctx.a.index.methods()) {
            if (m.name().equals("main") && m.type().hasFlags(Flag.Static) && m.hasBody()
                    && m.owner().decl().getLeadingAnnotations().stream()
                    .anyMatch(a -> Annotations.is(a, "org.springframework.boot.autoconfigure.SpringBootApplication"))) {
                return m;
            }
        }
        return null;
    }
}
