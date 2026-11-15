package dev.logast.support;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Error responses carry the trace id: a reported error leads straight to its log lines and trace. */
class ProblemTraceIdTest {

    @RestController
    static class Orders {
        @GetMapping("/orders/missing")
        String missing() {
            throw new IllegalArgumentException("no such order");
        }

        @GetMapping("/orders/traced")
        String traced() {
            throw new IllegalStateException("already traced");
        }
    }

    @RestControllerAdvice
    static class Handlers {
        @ExceptionHandler(IllegalArgumentException.class)
        ProblemDetail notFound(IllegalArgumentException e) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        }

        @ExceptionHandler(IllegalStateException.class)
        ProblemDetail own(IllegalStateException e) {
            ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            p.setProperty("traceId", "set-by-the-handler");
            return p;
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Orders())
            .setControllerAdvice(new Handlers(), new ProblemTraceId(() -> "4bf92f3577b34da6a3ce929d0e0e4736"))
            .build();

    @Test
    void anExceptionHandlersProblemDetailGetsTheTraceId() throws Exception {
        mvc.perform(get("/orders/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("no such order"))
                .andExpect(jsonPath("$.traceId").value("4bf92f3577b34da6a3ce929d0e0e4736"));
    }

    @Test
    void aTraceIdTheHandlerSetIsKept() throws Exception {
        mvc.perform(get("/orders/traced"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.traceId").value("set-by-the-handler"));
    }

    @Test
    void registeredOnlyWhenSwitchedOn() {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProblemTraceIdConfiguration.class))
                .withBean(Tracer.class, () -> Tracer.NOOP);
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ProblemTraceId.class));
        runner.withPropertyValues("logging-support.problem-trace-id.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ProblemTraceId.class));
    }
}
