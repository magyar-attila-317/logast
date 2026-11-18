# tracing pack

Every inbound and outbound hop carries the trace, spans end and carry their errors, the traces reach a backend, and
the log lines can be matched to them.

- **On by default** (`packs: [logging, tracing]`). `--pack tracing` works on its own: it brings the
  [runtime starter](../starter.md) it needs.
- ASYNC_CONTEXT_LOSS, SCHEDULED_TASK_UNTRACED, SSE_BROADCAST_CONTEXT and REACTIVE_CONTEXT_LOSS belong to both the logging and
  the tracing pack;
  OBSERVED_ANNOTATION_INACTIVE to both tracing and [metrics](metrics.md) (one order when both run).

Legend: [rules.md](../rules.md#how-to-read-the-rule-tables).

## Rules

| rule | severity | finds | fix |
|---|---|---|---|
| SETUP_OTEL_STARTER | high | no tracer bridge or exporter (Boot 4: `spring-boot-starter-opentelemetry`; Boot 3: actuator + `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`); conflicting setups (a Boot 4 starter in a Boot 3 build, Brave next to OpenTelemetry, the OpenTelemetry Spring starter or Java agent next to Boot's) | AUTO: adds the dependencies of the Boot generation in the build; report for conflicts |
| ENTRYPOINT_NOT_OBSERVED | high | an entrypoint Spring does not observe (`@EventListener` / `@TransactionalEventListener` starting a flow of its own, WebSocket handlers, batch runners and `main`, annotations from `entrypoints.extraAnnotations`, Kafka / RabbitMQ listeners until their observation is on, batch listeners, Spring Batch steps, `@SqsListener`, Redis pub/sub listeners, Spring Integration `@ServiceActivator` methods, gRPC calls with Spring gRPC's observation off): its logs have no trace id and it records no span | AUTO: `ObservedContext.entrypoint(…)` around the body (see above); report for a Redis adapter delegate logast cannot name and a gRPC call that completes after its method returns |
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
| SSE_BROADCAST_CONTEXT (shared with logging) | low | `SseEmitter.send` to the emitters of every subscriber held in a field (a loop over it, or a lambda passed to its `forEach` / `removeIf`) with a log line in the loop that does not name the subscriber. `send` writes on the calling thread ([Spring Framework, ResponseBodyEmitter](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/ResponseBodyEmitter.html)), so the line carries the sender's MDC and trace: there is no executor hop for the TaskDecorator or `ContextExecutors` to carry, and the subscriber's own request observation stays open until its async request ends. The subscriber's context reaches only the `onCompletion` / `onTimeout` / `onError` callbacks registered when it subscribed (ASYNC_CONTEXT_LOSS wraps them). STOMP is not reported: `ChannelInterceptor.preSend` runs on the sending thread before the channel hands the message to its executor, and Spring Boot gives the client inbound and outbound channels its `applicationTaskExecutor`, which the starter's TaskDecorator decorates | report: name the subscriber in the line (the map key, the subscriber's id) or log its outcome in the emitter's callback |
| REACTIVE_CONTEXT_LOSS (shared with logging) | high | an entrypoint returning a `Mono`/`Flux` (WebFlux controller or router handler, reactive listener) while Reactor restores ThreadLocals only in `handle`/`tap` (the default): log lines in the operators carry no trace id, spans started there lose their parent | AUTO: `spring.reactor.context-propagation: auto` where Spring Boot's ReactorAutoConfiguration is on the classpath (Boot 3.2+; Boot 4: `spring-boot-reactor`, which `spring-boot-starter-webflux` brings), else `Hooks.enableAutomaticContextPropagation()` first in `main`; report when `io.micrometer:context-propagation` is missing, the property is set to something else, or there is no `main` ([Reactive code](#reactive-code)) |
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
- **Functional endpoints** (Spring MVC and Spring WebFlux `RouterFunction`): the handler methods of
  `route().GET("/x", h::get)`, `path("/p", b -> …)` and `RouterFunctions.route(RequestPredicates.GET("/x"), h::get)` are
  HTTP entrypoints with their paths, observed like controllers (Boot reference, Metrics › Spring WebFlux: "all requests
  handled by Spring WebFlux controllers and functional handlers").
- **Redis pub/sub** (Spring Data Redis, `redis X.method`): a `MessageListener.onMessage` implementation, and the
  method a `new MessageListenerAdapter(delegate, "name")` calls (the name a literal or constant; `handleMessage` when
  none is given, or `setDefaultListenerMethod("…")`; overloads with the message and optionally the channel). Spring
  Data Redis observes Redis commands through Lettuce, not the listener container (reference, Observability), so each
  gets an ObservedContext. An adapter whose method name is not a constant, or whose delegate has no such source method,
  is reported where it is created. RedisMessageListenerContainer logs a listener's exception itself (WARN, when no
  ErrorHandler is set).
- **Spring Integration** `@ServiceActivator` methods (`@ServiceActivator <inputChannel>`): Spring Integration observes
  a handler only when its name matches an observation pattern (`@EnableIntegrationManagement(observationPatterns = …)`
  or `spring.integration.management.observation-patterns`, which Boot passes to it); by default none is instrumented
  (Spring Integration reference, Metrics and Management › Micrometer Observation). The handler's name is
  `<bean>.<method>.serviceActivator[.handler]`, matched like `PatternMatchUtils.smartMatch` (first match decides, `!`
  negates, `*` wildcard): matched methods get MDC and lines, the rest an ObservedContext. A `@Bean @ServiceActivator`
  method defines a handler bean and is no entrypoint. `@Transformer`, `@Filter`, `@Splitter` and `IntegrationFlow`
  lambdas are not seen yet.
- **gRPC services** (`grpc X.method`): the public `void` methods of a `BindableService` (the generated `*ImplBase`)
  whose last parameter is the `StreamObserver` (unary and server streaming). Spring gRPC observes every call
  (Boot's `GrpcServerObservationAutoConfiguration` registers `ObservationGrpcServerInterceptor` globally;
  `spring.grpc.server.observation.enabled`, default true, Boot properties appendix), so they get MDC only and no
  outcome line: the call ends with `onCompleted`, which may come after the method returns, and its status and the
  server observation are the outcome (like an HTTP access log). With the observation off or without Spring gRPC, a
  method that calls `responseObserver.onCompleted()` in its own body gets an ObservedContext; one that hands the
  observer to a lambda, an inner class or another call is reported (a wrapper would end before the call does).
  Client and bidirectional streaming methods return the request observer and are not seen.

### `@Observed` and `@NewSpan`

A method with `@Observed` (on it or its class) or `@NewSpan` counts as observed only while Spring Boot processes these
annotations: `management.observations.annotations.enabled: true` and aspectjweaver on the classpath (Boot reference,
Observability). Without them the annotation does nothing at runtime; the tracing pack switches them on, or the method
gets an ObservedContext like any other entrypoint.

### Opting in to more spans

logast gives entrypoints a span; the code below them (a service method under a controller, a listener running inside
the publisher's flow) shares the entrypoint's span. That is the default on purpose, and there is no policy switch to
change it. Where you want a span of its own, put Micrometer's `@Observed` on the method:

```java
@Observed(name = "berth.release")
public void release(String berthCode) { … }

@EventListener
@Observed(name = "berth.ledger-entry")
public void onReleased(BerthReleased event) { … }
```

What logast does with it: switches the annotations on (OBSERVED_ANNOTATION_INACTIVE: the property and the AspectJ
starter), and from then on counts the method as observed: no ObservedContext around it, no second business timer
(BUSINESS_EVENT_UNMEASURED), and no counter for a "… completed" / "… failed" line its observation already counts
(METRIC_OUTCOME_UNMEASURED). A constant name is fine for SPAN_NAME_DYNAMIC.

- **Naming**: a fixed, dotted name for the kind of work (`berth.release`), never an id; the name is the timer's name
  too (`berth.release` with `error`, `class`, `method` tags) and the span's contextual name. Ids go into MDC (logast
  scopes them) or `highCardinalityKeyValue(…)`.
- **Proxies**: the aspect works on Spring beans called through their proxy: not on `private` methods, and not when the
  bean calls its own method (`this.release(…)`).
- **Cost**: each annotated call is one more span exported to the tracing backend (with the sampling rate) and one more
  timer series in the metrics backend. Worth it for work that takes time or fails on its own (a downstream call, a
  batch step, a listener doing real work); not for helpers called per item in a loop, where it multiplies spans and
  hides the trace's shape.

### Reactive code

Spring WebFlux controller methods (`@RestController` returning `Mono`/`Flux`) and WebFlux router handlers are HTTP
entrypoints like their Spring MVC counterparts: WebFlux's server observation times each request (`http.server.requests`)
and carries its trace, so they get no wrapper. A method returning a `Mono`/`Flux` only assembles a pipeline that runs
later, on whichever thread emits, so logast edits it only where the edit is right at assembly time:

| in a reactive method | what logast does | why |
|---|---|---|
| ObservedContext / MdcScope around the body | never | it would close before the pipeline runs: the operators would run without it |
| business ids in MDC (MISSING_DOMAIN_MDC) | report | Reactor restores only the ThreadLocals registered in Micrometer's `ContextRegistry`, not arbitrary MDC keys: the note names `Slf4jThreadLocalAccessor` + `contextWrite`, or baggage |
| entry line (ENTRYPOINT_SILENT) | NEEDS TEXT, at the top of the body | the method runs once per request, inside the request's observation |
| outcome tag on the running timer (METRIC_OUTCOME_UNMEASURED) | not offered for a line inside an operator | the callback runs on another thread, where the request's timer is not the current observation |
| `onErrorReturn` / `onErrorComplete` / `onErrorResume` to a fallback without an error type (CATCH_ALL_DEFAULT) | report | every error becomes a value; the request ends as a success and its observation records no error |

The trace follows the pipeline only with Reactor's automatic context propagation (REACTIVE_CONTEXT_LOSS): "the
automatic mode, enabled via `Hooks.enableAutomaticContextPropagation()` [...] provides access to ThreadLocal values
throughout the reactive chain", while the default mode restores them only in `handle` and `tap`, and both need
`io.micrometer:context-propagation` (Reactor reference, Context-Propagation Support). Spring Boot calls the hook for
`spring.reactor.context-propagation: auto` (default `limited`; Boot reference, Observability › Context Propagation,
3.5 and 4.1). The property exists since Boot 3.2, and Boot 4 reads it in `spring-boot-reactor`, which
`spring-boot-starter-webflux` brings but a service with only `spring-webflux` (for `WebClient`) does not; there, and
on Boot 3.0/3.1, logast calls the hook first in `main` ("this mode applies only to new subscriptions, so it is
recommended to enable this hook when the application starts"). Micrometer Tracing puts the trace and span ids into
MDC from the restored observation, so log lines in the operators carry them.

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

## Business ids across services (baggage)

MDC stays inside a service: the next service gets the trace id, not your `tenantId`. Trace **baggage** carries a value
with the trace to every service downstream. logast adds none automatically; the starter has a helper for the places
you choose:

```java
try (var baggage = BusinessBaggage.with("tenantId", tenantId)) {
    shippingClient.book(order);      // the shipping service receives tenantId with the trace
}
```

`BusinessBaggage.with(key, value)` puts the value into baggage (`Tracer.createBaggageInScope`, Micrometer Tracing) and
into this service's MDC for the block; closing restores both. A null value changes nothing (an outer value stays).
Without Micrometer Tracing (no `Tracer` bean) only the MDC entry is set. In vendor mode logast copies the class into
the service once its code uses `BusinessBaggage` (and its Tracer wiring, `BusinessBaggageConfiguration`, when Micrometer
Tracing is on the classpath); otherwise it is not copied.

Spring Boot properties, the same names in Boot 3.5 and 4.x
([Boot reference, Tracing › Baggage](https://docs.spring.io/spring-boot/reference/actuator/tracing.html#actuator.micrometer-tracing.baggage);
application properties appendix, `management.tracing.baggage.*`):

```yaml
management:
  tracing:
    baggage:
      remote-fields: tenantId          # sent as a header of its own; W3C propagation also sends all baggage in `baggage`
      correlation:
        fields: tenantId               # receiving side: the value lands in MDC (and in every log line)
```

Boot's reference: baggage "is automatically propagated over the network if you're using W3C propagation. If you're
using B3 propagation, baggage is not automatically propagated"; `remote-fields` propagates it in both, and
`correlation.fields` puts it into the MDC. Each service that should log the value sets `correlation.fields`.

**When to use it**: a few low-volume business keys every service in a flow logs and someone searches by (tenant,
channel, a correlation id of the business process), where passing them as parameters through every API is not
possible.

**When not to**:
- Every downstream call carries it, to third parties too (a payment provider, a public API you call): headers leave
  your system. **Never personal data** (names, e-mails, customer numbers that identify a person, tokens).
- Size: it is sent on every hop; keep values short and fields few (implementations need only propagate 64 entries and
  8192 bytes in total, [W3C Baggage › Limits](https://www.w3.org/TR/baggage/#limits); servers limit header size too).
- Not for ids of one service's own work (an order id inside the order service): MDC (logast's MdcScope) is enough.
- Not as input: a downstream service must not trust baggage for authorisation or business decisions; anyone upstream can
  set it.
