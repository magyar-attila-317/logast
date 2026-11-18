package dev.logast.support;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.reactive.config.WebFluxConfigurer;

import java.util.List;
import java.util.function.Supplier;

/**
 * Spring WebFlux's counterpart of {@link ProblemTraceId}: puts the trace id as {@code "traceId"} into every
 * {@link ProblemDetail} response ({@code @ExceptionHandler} methods and Spring's own handling). A traceId already set is
 * kept. WebFlux has no ResponseBodyAdvice; it calls the {@link ErrorResponse.Interceptor}s registered through a
 * {@link WebFluxConfigurer} for every RFC 9457 response it renders (Spring Framework reference, WebFlux › Error
 * Responses › Render; since 6.2).
 * <p>The trace is read from the current observation, which WebFlux keeps in the Reactor context: it is there when
 * Reactor restores ThreadLocals in its operators ({@code spring.reactor.context-propagation: auto}); without it no
 * traceId is added.
 * <p>On only with {@code logging-support.problem-trace-id.enabled=true} in a reactive web application (logast's
 * ERROR_RESPONSE_UNTRACED sets it). The conditions sit on the class itself: a copied (vendored) version is found by
 * component scan, not through {@link ProblemTraceIdConfiguration}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
@ConditionalOnProperty(name = "logging-support.problem-trace-id.enabled", havingValue = "true")
public class ReactiveProblemTraceId implements WebFluxConfigurer, ErrorResponse.Interceptor {
    static final String PROPERTY = "traceId";

    private final Supplier<String> traceId;

    @Autowired
    public ReactiveProblemTraceId(ObjectProvider<Tracer> tracer) {
        this(() -> {
            Tracer t = tracer.getIfAvailable();
            Span span = t == null ? null : t.currentSpan();
            return span == null ? null : span.context().traceId();
        });
    }

    ReactiveProblemTraceId(Supplier<String> traceId) {
        this.traceId = traceId;
    }

    @Override
    public void addErrorResponseInterceptors(List<ErrorResponse.Interceptor> interceptors) {
        interceptors.add(this);
    }

    @Override
    public void handleError(ProblemDetail detail, ErrorResponse errorResponse) {
        if (detail.getProperties() == null || !detail.getProperties().containsKey(PROPERTY)) {
            String id = traceId.get();
            if (id != null) {
                detail.setProperty(PROPERTY, id);
            }
        }
    }
}
