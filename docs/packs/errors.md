# errors pack

What an HTTP client sees when something goes wrong: leaked internals, exceptions that become 500s, error responses
without a trace id, failures that leave no stack trace.

- **Opt-in:** `packs: [logging, tracing, errors]` in `logast.yaml`, or `logast scan . --pack errors`.
- ERROR_RESPONSE_UNTRACED uses one class of the [runtime starter](../starter.md) (`ProblemTraceId`) and switches on
  only its property, `logging-support.problem-trace-id.enabled`.

Legend: [rules.md](../rules.md#how-to-read-the-rule-tables).

## Rules

| rule | severity | finds | fix |
|---|---|---|---|
| ERROR_DETAILS_EXPOSED | high | `server.error.include-message/stacktrace/exception`; a handler for unexpected exceptions copying `e.getMessage()` into the body | AUTO for the config (set to never), report for handlers |
| UNMAPPED_EXCEPTION | medium | an application exception thrown on an HTTP path that no `@ExceptionHandler` or `@ResponseStatus` maps (it becomes a 500); a downstream call on an HTTP path (RestClient, RestTemplate, `@HttpExchange` / Feign interfaces, a WebClient call ending in `block()`) whose exceptions no handler maps, so another service's failure is answered as our own 500. Handlers count from the sources and from `@ControllerAdvice` classes in libraries on the classpath (outside the public groups, without a basePackages / assignableTypes selector) | report: decide its status (downstream: 502 for an error status, 504 for a timeout, or pass a 404 on) |
| CLIENT_ERROR_AS_SERVER_ERROR | medium | a handler answering 500 for a validation / not-found / denied exception; a catch-all `@ExceptionHandler(Exception)` answering 500 while request bodies are `@Valid`: it takes `MethodArgumentNotValidException` (WebFlux: `WebExchangeBindException`) before Spring's default 400 | report |
| PROBLEM_DETAILS_DISABLED | low | Spring MVC errors are not RFC 9457 `ProblemDetail` responses: neither `spring.mvc.problemdetails.enabled: true` nor an advice extending `ResponseEntityExceptionHandler` (on a [WebFlux-only service](#webflux-only-services): `spring.webflux.problemdetails.enabled`, WebFlux's `ResponseEntityExceptionHandler`) | report (changes the API's error body) |
| ERROR_RESPONSE_UNTRACED | medium | error responses without the trace id (Spring MVC, or a [WebFlux-only service](#webflux-only-services), with Micrometer Tracing) | AUTO: `logging-support.problem-trace-id.enabled: true`; logging-support then adds `"traceId"` to every `ProblemDetail` and to Spring Boot's error map, so a reported error leads straight to its log lines and trace (a traceId a handler set is kept). Report on a WebFlux-only service: logging-support's `ProblemTraceId` is a Spring MVC advice |
| ASYNC_ERROR_LOST | high | a Future whose result is thrown away: its exception stays in the future and is never logged (`@Async void` is fine: Spring logs those) | AUTO for a `CompletableFuture` statement: a `whenComplete` that logs the failure (wording marked `// logast:wording`); report for other Futures (`executor.submit(…)`) |
| CAUSE_DROPPED | medium | `catch (X e) { throw new Y(e.getMessage()); }`: the new exception does not carry `e`, so the original stack trace is lost | AUTO when `Y` has a constructor taking the same arguments plus the cause (`e` is appended); report otherwise (add such a constructor, or `initCause(e)`) |
| CATCH_ALL_DEFAULT | medium | `catch (Exception e) { … return null; }` (or `Optional.empty()`, an empty collection, `0`, `false`, `""`): the caller cannot tell a failure from an empty result, logged or not. The Reactor form too: `onErrorReturn(x)`, `onErrorComplete()`, `onErrorResume(e -> Mono.just(..) / Mono.empty())` without an error type or predicate; in WebFlux the request then ends as a success, and the server observation records no error ([Framework reference, Observability, reactive HTTP server](https://docs.spring.io/spring-framework/reference/integration/observability.html)) | report (let it through, catch only what means "nothing there", or return a result type that carries the failure) |
| UNEXPECTED_ERROR_UNLOGGED | high | an `@ExceptionHandler(Exception / RuntimeException / Throwable)` that does not log the exception: Spring logs nothing for a handled exception, so the failure leaves no stack trace | AUTO: `log.error(…, e)` at the top of the handler (wording marked `// logast:wording`, logger added if missing); not when it rethrows or passes the exception on (`super.handleException(…)`) |
| ERROR_HANDLER_STATUS_OK | medium | an `@ExceptionHandler` that answers 200: a body object without `@ResponseStatus` (on it or its class), or `ResponseEntity.ok(…)` (a `Mono<T>` answers what `T` answers; a handler taking the response, Servlet's or WebFlux's `ServerHttpResponse` / `ServerWebExchange`, sets its own status); a returned `ProblemDetail` / `ErrorResponse` carries its own status, except a `ProblemDetail` subclass built without one (status 0: the handler fails and the client gets the default answer, usually 500) | report (the status is a decision) |
| TX_COMMITS_ON_CHECKED | medium | a `@Transactional` method (or class) that throws a checked exception without `rollbackFor` / `noRollbackFor`: checked exceptions do not roll back by default, so the work before the exception is committed | report (`rollbackFor` changes what is committed); not with `@EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)` |
| REQUEST_NOT_VALIDATED | medium | a `@RequestBody` / `@ModelAttribute` (also on the interface method it implements) whose type has Bean Validation constraints, without `@Valid` / `@Validated`: the constraints are never checked | report (`@Valid` changes which requests are accepted); the note says whether a catch-all handler would answer the rejection 500 instead of 400, and when no Bean Validation provider is on the classpath |

## WebFlux-only services

A service with Spring WebFlux and without Spring MVC runs as a reactive web application; with both, Spring Boot runs
Spring MVC ([Boot reference, SpringApplication, Web Environment](https://docs.spring.io/spring-boot/reference/features/spring-application.html#features.spring-application.web-environment)),
and the rules keep their MVC behaviour. On a WebFlux-only service:

- PROBLEM_DETAILS_DISABLED names `spring.webflux.problemdetails.enabled` (Boot 3.5 and 4.x
  [properties appendix](https://docs.spring.io/spring-boot/appendix/application-properties/index.html), default `false`)
  and WebFlux's `ResponseEntityExceptionHandler` (`org.springframework.web.reactive.result.method.annotation`,
  [Framework reference, WebFlux Error Responses](https://docs.spring.io/spring-framework/reference/web/webflux/ann-rest-exceptions.html));
  an advice extending it, in the sources or a library, settles it.
- CLIENT_ERROR_AS_SERVER_ERROR and REQUEST_NOT_VALIDATED name `WebExchangeBindException`, what a failed `@Valid` body
  raises in WebFlux; a handler for it settles the catch-all finding.
- ERROR_RESPONSE_UNTRACED is a report: logging-support's `ProblemTraceId` is a Spring MVC `ResponseBodyAdvice`, so
  nothing is switched on; add `"traceId"` to the `ProblemDetail` responses in the handlers.
- `@ExceptionHandler` in a `@RestControllerAdvice`, `@ResponseStatus` and `server.error.*` work the same in WebFlux, and
  so do the other rules. An exception a handler answers is not recorded as an error on the server observation, in
  WebFlux as in MVC ([Framework reference, Observability, HTTP Server instrumentation](https://docs.spring.io/spring-framework/reference/integration/observability.html#observability.http-server)):
  a handled failure leaves its stack trace only where the handler logs it (UNEXPECTED_ERROR_UNLOGGED).
