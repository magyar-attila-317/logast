package dev.logast.support;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/** WebFlux error responses carry the trace id too (#103): ProblemDetails from handlers and from Spring's own handling. */
class ReactiveProblemTraceIdTest {

    @RestController
    static class Orders {
        @GetMapping("/orders/missing")
        Mono<String> missing() {
            return Mono.error(new IllegalArgumentException("no such order"));
        }

        @GetMapping("/orders/traced")
        Mono<String> traced() {
            return Mono.error(new IllegalStateException("already traced"));
        }
    }

    @RestControllerAdvice
    static class Handlers extends ResponseEntityExceptionHandler {
        @ExceptionHandler(IllegalArgumentException.class)
        Mono<ProblemDetail> notFound(IllegalArgumentException e) {
            return Mono.just(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage()));
        }

        @ExceptionHandler(IllegalStateException.class)
        ProblemDetail own(IllegalStateException e) {
            ProblemDetail p = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            p.setProperty("traceId", "set-by-the-handler");
            return p;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class App {
        @Bean
        Orders orders() {
            return new Orders();
        }

        @Bean
        Handlers handlers() {
            return new Handlers();
        }

        @Bean
        ReactiveProblemTraceId problemTraceId() {
            return new ReactiveProblemTraceId(() -> "4bf92f3577b34da6a3ce929d0e0e4736");
        }
    }

    private static WebTestClient client(AnnotationConfigApplicationContext ctx) {
        return WebTestClient.bindToApplicationContext(ctx).build();
    }

    @Test
    void anExceptionHandlersProblemDetailGetsTheTraceId() {
        try (var ctx = new AnnotationConfigApplicationContext(App.class)) {
            client(ctx).get().uri("/orders/missing").exchange()
                    .expectStatus().isNotFound()
                    .expectBody()
                    .jsonPath("$.detail").isEqualTo("no such order")
                    .jsonPath("$.traceId").isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        }
    }

    @Test
    void springsOwnProblemDetailGetsTheTraceId() {   // a 405 answered by ResponseEntityExceptionHandler
        try (var ctx = new AnnotationConfigApplicationContext(App.class)) {
            client(ctx).post().uri("/orders/missing").exchange()
                    .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED)
                    .expectBody()
                    .jsonPath("$.traceId").isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        }
    }

    @Test
    void aTraceIdTheHandlerSetIsKept() {
        try (var ctx = new AnnotationConfigApplicationContext(App.class)) {
            client(ctx).get().uri("/orders/traced").exchange()
                    .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                    .expectBody()
                    .jsonPath("$.traceId").isEqualTo("set-by-the-handler");
        }
    }

    @Test
    void registeredOnlyWhenSwitchedOnInAReactiveApplication() {
        ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProblemTraceIdConfiguration.class))
                .withBean(Tracer.class, () -> Tracer.NOOP);
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(ReactiveProblemTraceId.class));
        runner.withPropertyValues("logging-support.problem-trace-id.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ReactiveProblemTraceId.class).doesNotHaveBean(ProblemTraceId.class));
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProblemTraceIdConfiguration.class))
                .withBean(Tracer.class, () -> Tracer.NOOP)
                .withPropertyValues("logging-support.problem-trace-id.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ProblemTraceId.class).doesNotHaveBean(ReactiveProblemTraceId.class));
    }
}
