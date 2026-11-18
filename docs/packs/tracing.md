# tracing pack

Every inbound and outbound hop carries the trace, spans end and carry their errors, the traces reach a backend, and
the log lines can be matched to them.

- **On by default** (`packs: [logging, tracing]`). `--pack tracing` works on its own: it brings the
  [runtime starter](../starter.md) it needs.
- ASYNC_CONTEXT_LOSS and SCHEDULED_TASK_UNTRACED belong to both the logging and the tracing pack;
  OBSERVED_ANNOTATION_INACTIVE to both tracing and [metrics](metrics.md) (one order when both run).

Legend: [rules.md](../rules.md#how-to-read-the-rule-tables).

## Rules

| rule | severity | finds | fix |
|---|---|---|---|
| SETUP_OTEL_STARTER | high | no tracer bridge or exporter (Boot 4: `spring-boot-starter-opentelemetry`; Boot 3: actuator + `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`); conflicting setups (a Boot 4 starter in a Boot 3 build, Brave next to OpenTelemetry, the OpenTelemetry Spring starter or Java agent next to Boot's) | AUTO: adds the dependencies of the Boot generation in the build; report for conflicts |
| ENTRYPOINT_NOT_OBSERVED | high | an entrypoint Spring does not observe (`@EventListener` / `@TransactionalEventListener` starting a flow of its own, WebSocket handlers, batch runners and `main`, annotations from `entrypoints.extraAnnotations`, Kafka / RabbitMQ listeners until their observation is on, batch listeners): its logs have no trace id and it records no span | AUTO: `ObservedContext.entrypoint(…)` around the body (see above) |
| OBSERVED_ANNOTATION_INACTIVE | high | `@Observed` / `@NewSpan` without `management.observations.annotations.enabled: true` or without aspectjweaver: the annotated methods run without an observation or span | AUTO: sets the property and adds `spring-boot-starter-aspectj` (Boot 4) / `spring-boot-starter-aop` (Boot 3); report when the build file or Boot's observation auto-configuration is missing. Also switches on `@Timed` / `@Counted`. In the metrics pack too, for `@Observed` / `@Timed` / `@Counted` (the same switch) |
| HTTP_CLIENT_UNTRACED | high | `RestTemplate` / `RestClient` / `WebClient` built by hand instead of from Spring Boot's builder, JDK `HttpClient`, Apache, OkHttp, OpenFeign without `feign-micrometer`: no `traceparent` header, the called service starts a new trace | AUTO where a Boot builder can be injected; report otherwise (Feign: add `feign-micrometer`) |
| KAFKA_OBSERVATION_DISABLED / RABBIT_OBSERVATION_DISABLED | high | `KafkaTemplate` / `@KafkaListener`, `@RabbitListener` without observation (off by default): the trace stops at the topic or queue | AUTO: `spring.kafka.template/listener.observation-enabled`, `spring.rabbitmq.listener.*.observation-enabled` |
| JOB_LAUNCH_UNTRACED | high | a Kubernetes container (fabric8, official client) or `ProcessBuilder` started without `TRACEPARENT` in its environment: the job runs as a new, unrelated trace | AUTO: adds `TraceContextEnv.current()` to the environment (the starter's batch-trace listener continues it); report where it cannot be added |
| TRACING_SAMPLING | medium | `management.tracing.sampling.probability` below 1.0, or not set (Boot keeps 10 %): most trace ids in the logs lead to no trace | AUTO: 1.0 when not set (sample in the collector instead); report for a lower value |
| SPAN_NOT_CLOSED | medium | an `Observation` / Micrometer `Span` / `ScopedSpan` / OpenTelemetry `Span` started by hand into a local variable that is not ended in a `finally` block, records no failure (`error(e)`, `recordException`), or is never put in scope (`openScope`, `tracer.withSpan`, `makeCurrent`): leaked spans, failures shown as successes, log lines without the span | report, with the try/catch/finally form (or `observation.observe(() -> …)`); not when the variable leaves the method (returned, passed on, ended in a callback) |
| SPAN_NAME_DYNAMIC | high | an observation or span name built from an id (`Observation.createNotStarted("load." + orderId, …)`, `tracer.nextSpan().name(…)`, `startScopedSpan`, `contextualName`, OpenTelemetry `spanBuilder` / `updateName`), or an id as a low-cardinality key value: Micrometer names the observation's timer after it and tags it with every low-cardinality key value ([Micrometer, Observation Handler](https://docs.micrometer.io/micrometer/reference/observation/components.html): "Only low cardinality key values are used as meter tags"), and a span name should identify a class of spans, not one call ([OpenTelemetry Tracing API](https://opentelemetry.io/docs/specs/otel/trace/api/)): one timer series and span name per value. An id is what METRIC_HIGH_CARDINALITY_TAG calls one (a vocabulary key with high cardinality, PII), or a value named `id` / `…Id` / `uuid` | report: a fixed name, the id in `highCardinalityKeyValue(…)` or a span tag; names passed in by a caller or built from bounded values are not reported |
| MANUAL_TRACE_MDC | medium | `traceId` / `spanId` put into MDC by hand: Micrometer Tracing maintains them | report: delete the manual MDC handling |
| TRACING_EXPORT_UNCONFIGURED | medium | Micrometer Tracing with OpenTelemetry, but no OTLP endpoint anywhere (`management.opentelemetry.tracing.export.otlp.endpoint`, Boot 3: `management.otlp.tracing.endpoint`, in any application*.yml or profile, or `OTEL_EXPORTER_OTLP_(TRACES_)ENDPOINT` / the property's environment variable in the project's deployment files; logast's own output kept in the tree, such as a saved scan, does not count): Spring Boot then creates no exporter and the trace ids in the logs lead nowhere; also export switched off in the base configuration | report (the endpoint is per environment) |
| TRACE_ID_NOT_LOGGED | medium | a `logback-spring.xml` / `logback.xml` text `<pattern>` without the trace id (`%X{traceId}`, `%correlationId`, `${LOG_CORRELATION_PATTERN}` or Boot's default pattern), or a `logging.pattern.console` / `.file` replacing Boot's pattern without it: Spring Boot adds the correlation id only through its own pattern ([Boot reference, Tracing, Logging Correlation IDs](https://docs.spring.io/spring-boot/reference/actuator/tracing.html)), so the log lines cannot be matched to a trace. Only without the logging pack: there SETUP_LOGBACK_XML replaces the file with structured logging, which writes every MDC value | report: add `%X{traceId:-} %X{spanId:-}` or `${LOG_CORRELATION_PATTERN:-}`, or switch to structured logging |
| ASYNC_CONTEXT_LOSS, SCHEDULED_TASK_UNTRACED (shared with logging) | high / medium | work handed to another thread (`CompletableFuture.*Async` without an executor, raw executors, parallel streams, `@Async` without the TaskDecorator) and tasks an executor runs periodically: their logs and spans lose the trace | AUTO where a context-propagating wrapper fits (`ContextExecutors.wrap`, `ObservedContext.observed(…)`, the starter's TaskDecorator); report otherwise |
| RUNTIME_TRACE_LOST (`logast verify`) | high | a test's log line on another thread without the trace id the flow had before | report |

## Entrypoints Spring does not observe

Entrypoint the framework doesn't trace (`@EventListener`, custom listeners), with the ids from its parameters:
```java
ObservedContext.entrypoint("inbox-processor.handle")
        .mdc("order.id", event.orderId())
        .run(() -> {
            ...
        });
// with a result: return ObservedContext.entrypoint("…").call(() -> { … });
```
It records failures on the span and metrics, and logs the outcome once, with the MDC keys and trace id:
`inbox-processor.handle completed [durationMs=12]` (INFO; DEBUG for per-message listeners), or
`inbox-processor.handle failed [durationMs=12]` (ERROR, with the exception). Checked exceptions pass through.
The exception is always rethrown (rollback, retries and dead-letter routing depend on it). A synchronous
`@EventListener` gets `.failureLoggedByCaller()`: the exception goes back to the publisher's code, which logs it, so
the wrapper's failure line drops to DEBUG and one failure stays one ERROR. `.describe("Inbox handling")` sets the
wording, `.quiet()` turns the lines off. In vendor mode, copied support classes from an older logast are updated by
an AUTO order (the dry run shows the diff). Where a lambda can't compile (a
parameter reassigned in the body, several checked exceptions), logast writes the same as try/catch/finally.

### Which listeners are entrypoints

A listener for an event the code publishes itself (`publisher.publishEvent(new OrderPlaced(…))`) is no entrypoint:
Spring calls it inside `publishEvent`, on the publisher's thread, so it is part of the publisher's flow (its MDC and
span; `@Async` listeners get them through the TaskDecorator). logast follows that call like any other, so the
listener's lines drop keys the publisher's flow already has in MDC and it gets no outcome line of its own.

A `@TransactionalEventListener` runs when the transaction commits (AFTER_COMMIT by default), after the publisher's
scopes may have closed: it stays an entrypoint of its own (only `phase = BEFORE_COMMIT` joins the publisher's flow).

Listeners Spring observes itself get no wrapper of their own (that would be a second span): `@JmsListener` (Spring Boot
sets the ObservationRegistry on the container factory), `@KafkaListener` and `@RabbitListener` once
`spring.kafka.listener.observation-enabled` / `spring.rabbitmq.listener.{simple,direct}.observation-enabled` is on
(KAFKA_OBSERVATION_DISABLED and RABBIT_OBSERVATION_DISABLED add it). Batch listeners (`batch = "true"`, a `List` or
`ConsumerRecords` parameter) are never observed per message, so they keep the ObservedContext wrapper.

### More entrypoints

More entrypoints, each checked against its framework's documentation:
- **Spring Batch** (`tasklet X.execute` once per step run; `item processor X.process` / `item writer X.write` per
  item or chunk, as classes): Batch opens a span per step only when it has the ObservationRegistry, which
  `BatchObservabilityBeanPostProcessor` sets. Batch 6's `DefaultBatchConfiguration` (which Boot 4 extends) and
  `@EnableBatchProcessing` register it; Boot 3's auto-configuration with Batch 5 does not, so there the step code gets
  an ObservedContext (Spring Batch reference, Monitoring and metrics › Tracing).
- **`@SqsListener`** (Spring Cloud AWS): each message is observed only with
  `spring.cloud.aws.sqs.observation-enabled: true` (default false); a batch listener never (Spring Cloud AWS reference,
  SQS › Observability).
- **Spring Cloud Stream** functions (`stream <name>-in-0`): the method a bound `@Bean Consumer`/`Function` returns as a
  method reference or a one-call lambda. Bound are the names in `spring.cloud.function.definition`, else the single
  functional bean. Stream observes each invocation (ObservationFunctionAroundWrapper), so they get MDC and lifecycle
  lines, no wrapper. A lambda with a body of its own has no method to scope and is not seen; a `Supplier` sends.
- **Functional endpoints** (Spring MVC `RouterFunction`): the handler methods of `route().GET("/x", h::get)`,
  `path("/p", b -> …)` and `RouterFunctions.route(RequestPredicates.GET("/x"), h::get)` are HTTP entrypoints with their
  paths, observed like controllers.

### `@Observed` and `@NewSpan`

A method with `@Observed` (on it or its class) or `@NewSpan` counts as observed only while Spring Boot processes these
annotations: `management.observations.annotations.enabled: true` and aspectjweaver on the classpath (Boot reference,
Observability). Without them the annotation does nothing at runtime; the tracing pack switches them on, or the method
gets an ObservedContext like any other entrypoint.

### Reactive code

A method returning a `Mono`/`Flux` only assembles a pipeline that runs later, elsewhere: logast puts no MdcScope or
ObservedContext around it (they would end before the work starts). Use Reactor's automatic context propagation
(`Hooks.enableAutomaticContextPropagation()` with context-propagation on the classpath) for MDC in reactive code.

## What the fixes look like

Context lost on a thread hand-off:
```java
private final ExecutorService executor = ContextExecutors.wrap(Executors.newFixedThreadPool(2));
```

Traces across services and processes:
```java
public ShippingClient(RestClient.Builder restClientBuilder) {         // was RestClient.builder()
    this.client = restClientBuilder.baseUrl("http://shipping-service").build();
}
pb.environment().putAll(TraceContextEnv.current());                    // orchestrator → batch job pod/process
```
