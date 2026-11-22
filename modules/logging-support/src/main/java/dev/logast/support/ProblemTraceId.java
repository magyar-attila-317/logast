package dev.logast.support;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Puts the trace id into error responses, so a client that reports an error can quote it and it leads straight to the
 * log lines and the trace: {@code "traceId"} on every {@link ProblemDetail} (from {@code @ExceptionHandler} methods and
 * Spring's own handling) and on Spring Boot's error map ({@code /error}). A traceId already set is kept.
 * <p>On only with {@code logging-support.problem-trace-id.enabled=true} (logast's ERROR_RESPONSE_UNTRACED sets it). The
 * conditions sit on the class itself: a copied (vendored) version is found by component scan, not through
 * {@link ProblemTraceIdConfiguration}.
 */
@ControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
@ConditionalOnProperty(name = "logging-support.problem-trace-id.enabled", havingValue = "true")
public class ProblemTraceId implements ResponseBodyAdvice<Object> {
    static final String PROPERTY = "traceId";

    private final Supplier<String> traceId;

    @Autowired
    public ProblemTraceId(ObjectProvider<Tracer> tracer) {
        this(() -> {
            Tracer t = tracer.getIfAvailable();
            Span span = t == null ? null : t.currentSpan();
            return span == null ? null : span.context().traceId();
        });
    }

    ProblemTraceId(Supplier<String> traceId) {
        this.traceId = traceId;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
                                  Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey(PROPERTY)) {
                String id = traceId.get();
                if (id != null) {
                    problem.setProperty(PROPERTY, id);
                }
            }
            return problem;
        }
        // Spring Boot's error attributes (BasicErrorController): timestamp, status, error, path
        if (body instanceof Map<?, ?> map && map.containsKey("status") && map.containsKey("error") && !map.containsKey(PROPERTY)) {
            String id = traceId.get();
            if (id != null) {
                Map<Object, Object> withId = new LinkedHashMap<>(map);
                withId.put(PROPERTY, id);
                return withId;
            }
        }
        return body;
    }
}
