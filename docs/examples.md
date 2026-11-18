# Examples: what `logast apply` changes

Before and after, per pack. Every snippet is shortened from the end-to-end test service (`e2e/before` →
`e2e/expected`, and the scenario snapshots in `e2e/expected-*`); `// …` marks lines left out, and a few long lines
are wrapped.

A line ending in `// logast:wording` carries a message logast wrote for review; `// logast:metric review` marks a
metric it added. What logast cannot decide on its own, it reports instead of changing ([last section](#audit)).

- [Logging: messages](#logging-messages)
- [Logging: exceptions](#logging-exceptions)
- [MDC context](#mdc-context)
- [Tracing](#tracing)
- [Metrics](#metrics)
- [Errors](#errors)
- [Resilience](#resilience)
- [Audit](#audit)

---

## Logging: messages

### String concatenation becomes a placeholder with a `[k=v]` key

The value becomes a searchable field, and the message is not built when the level is off.
*Scenario `expected-encodings`, `PriceListImport.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
public void load(String listId) {
    log.info("Liste importée: " + listId);
}
```

</td>
<td>

```java
public void load(String listId) {
    log.info("Liste importée [listId={}]", listId);
}
```

</td>
</tr>
</table>

### A scheduled job says when it starts and ends; the per-item line moves to DEBUG

The completed line carries the count and goes out only after the transaction commits, so a rollback is never
reported as done. *`e2e/before` → `e2e/expected`, `PledgeCleanupJob.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
@Scheduled(cron = "0 0 2 * * *")
@Transactional
public void cleanup() {
    for (String pledgeId : repository.findOpen()) {
        log.info("Closing stale pledge {}", pledgeId);
        repository.close(pledgeId);
    }
}
```

</td>
<td>

```java
@Scheduled(cron = "0 0 2 * * *")
@Transactional
public void cleanup() {
    log.info("Pledge cleanup started"); // logast:wording
    var pledgeIds = repository.findOpen();
    for (String pledgeId : pledgeIds) {
        log.debug("Closing stale pledge [pledgeId={}]", pledgeId);
        repository.close(pledgeId);
    }
    AfterCommit.log(() -> log.info("Pledge cleanup completed [pledgeCount={}]", pledgeIds.size())); // logast:wording
}
```

</td>
</tr>
</table>

### Structured JSON logs with the service name and version

Spring Boot's own structured logging in the format the policy chooses (here Logstash), no `logback-spring.xml`.
*`e2e/before` → `e2e/expected`, `application.yml`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```yaml
spring:
  application:
    name: e2e-shop
logging:
  structured:
    format:
      console: ecs
```

</td>
<td>

```yaml
spring:
  application:
    name: e2e-shop
    version: ${APPLICATION_VERSION:unknown}
logging:
  structured:
    format:
      console: logstash
    json:
      add:
        service:
          name: ${spring.application.name}
          version: ${spring.application.version}
```

</td>
</tr>
</table>

## Logging: exceptions

### A swallowed exception gets a log line

An empty catch leaves no trace of the failure. logast adds the line with the logger already in scope (here inherited
from the base class); the message is written by a person or an LLM. *Scenario `expected-logger-in-scope`,
`CleanupJob.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
void run(Runnable step) {
    try {
        step.run();
    } catch (IllegalStateException e) {
    }
}
```

</td>
<td>

```java
void run(Runnable step) {
    try {
        step.run();
    } catch (IllegalStateException e) {
        log.warn("Step failed", e);
        LoggersMetrics.stepFailed(); // logast:metric review, confidence: medium
    }
}
```

</td>
</tr>
</table>

### Logged and rethrown: the extra line goes

The exception is logged where it is handled, once. logast deletes a log-and-rethrow line only when every caller path
already logs the exception; otherwise it reports it. *`e2e/before` → `e2e/expected`, `PledgeLookup.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
public void reload() {
    try {
        cache.clear();
        load("all");
    } catch (IllegalStateException e) {
        log.error("Pledge reload failed", e);
        throw e;
    }
}
```

</td>
<td>

```java
public void reload() {
    try {
        cache.clear();
        load("all");
    } catch (IllegalStateException e) {
        throw e;
    }
}
```

</td>
</tr>
</table>

## MDC context

### The business id goes into MDC for the whole operation, and leaves the messages

Every line of the operation carries `pledgeId`, including those of the code it calls; the messages stop repeating
it. *`e2e/before` → `e2e/expected`, `PledgeService.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
log.info("Pledge allotment started [pledgeId={}]",
        request.code());
sender.send(request.code());
log.info("Pledge allotment completed [pledgeId={}, tableId={}]",
        request.code(), request.tableId());
```

</td>
<td>

```java
try (var mdc = MdcScope.of("pledgeId", request.code())) {
    log.info("Pledge allotment started");
    sender.send(request.code());
    log.info("Pledge allotment completed [tableId={}]",
            request.tableId());
}
```

</td>
</tr>
</table>

### The scope opens at the entrypoint; code further down drops the id from its messages

*`e2e/before` → `e2e/expected`, `PledgeController.java` and `PledgeService.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
// PledgeController
@PostMapping("/pledges/{pledgeId}/publish")
public void publish(@PathVariable String pledgeId) {
    pledges.publish(pledgeId);
}

// PledgeService
public void publish(String pledgeId) {
    log.info("Publishing pledge {}", pledgeId);
    sender.send(pledgeId);
    repository.close(pledgeId);
    log.info("Pledge {} published", pledgeId);
}
```

</td>
<td>

```java
// PledgeController
@PostMapping("/pledges/{pledgeId}/publish")
public void publish(@PathVariable String pledgeId) {
    try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
        pledges.publish(pledgeId);
    }
}

// PledgeService
public void publish(String pledgeId) {
    log.info("Publishing pledge");
    sender.send(pledgeId);
    repository.close(pledgeId);
    log.info("Pledge published");
}
```

</td>
</tr>
</table>

### `MDC.putCloseable` becomes `MdcScope.of`

`MDC.putCloseable` removes the key on close instead of restoring the value an outer scope had set.
*`e2e/before` → `e2e/expected`, `BaseRunJob.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
String runCode = resolveRunCode();
Week week = Week.current();
try (var mdc = MDC.putCloseable("runCode", runCode)) {
    doRun(runCode, week);
}
```

</td>
<td>

```java
String runCode = resolveRunCode();
Week week = Week.current();
try (var mdc = MdcScope.of("runCode", runCode)) {
    doRun(runCode, week);
}
```

</td>
</tr>
</table>

## Tracing

### An entrypoint Spring does not observe gets its own observation

An `@EventListener` that starts a flow of its own had no trace id in its logs and recorded no span.
*`e2e/before` → `e2e/expected`, `SettlementJobExecutor.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
@EventListener(ApplicationReadyEvent.class)
public void onReady() {
    job.execute();
}
```

</td>
<td>

```java
@EventListener(ApplicationReadyEvent.class)
public void onReady() {
    ObservedContext.entrypoint(JobMetrics.SETTLEMENT_JOB_EXECUTOR_ON_READY)
            .failureLoggedByCaller()
            .run(() -> {
                job.execute();
            });
}
```

</td>
</tr>
</table>

### A Redis pub/sub delegate gets its observation and MDC

A bean Redis calls through `new MessageListenerAdapter(relay, "onPledge")` ran without a trace and without the pledge
id in MDC: Spring Data Redis observes Redis commands, not the listener container.
*Scenario `expected-relay-entrypoints`, `PledgeRelay.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
public void onPledge(String pledgeId) {
    relayLog.record(pledgeId);
}
```

</td>
<td>

```java
public void onPledge(String pledgeId) {
    ObservedContext.entrypoint(RelayMetrics.PLEDGE_RELAY_ON_PLEDGE)
            .mdc("pledgeId", pledgeId)
            .debugOnSuccess()
            .run(() -> {
                relayLog.record(pledgeId);
            });
}
```

</td>
</tr>
</table>

### `@Observed` starts working: the property and the AspectJ starter are added

Without `management.observations.annotations.enabled` and aspectjweaver, Spring Boot ignores `@Observed` and the
method records nothing. The metrics pack does the same for `@Timed` / `@Counted` (scenario
`expected-metrics-annotations`). *Scenario `expected-tracing-pack`, `StockTracker.java`, `build.gradle.kts`,
`application.yml`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
@EventListener(ApplicationReadyEvent.class)
@Observed(name = "stock.warm-up")
void warmUp() {
```

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
```

</td>
<td>

```java
@EventListener(ApplicationReadyEvent.class)
@Observed(name = "stock.warm-up")
void warmUp() {
```

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-aspectj")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
```

```yaml
management:
  observations:
    annotations:
      enabled: true
```

</td>
</tr>
</table>

### A hand-built `WebClient` is built from Spring Boot's builder

The static `WebClient.builder()` gets no auto-configuration: no `traceparent` header (the called service starts a new
trace) and no timeout from `spring.http.clients.*`. *Scenario `expected-resilience-rollout`, `DepotClient.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
public DepotClient(ClientHttpConnector connector) {
    this.depots = WebClient.builder()
            .clientConnector(connector)
            .baseUrl("http://depots").build();
}
```

</td>
<td>

```java
public DepotClient(ClientHttpConnector connector,
        WebClient.Builder webClientBuilder) {
    this.depots = webClientBuilder
            .clientConnector(connector)
            .baseUrl("http://depots").build();
}
```

</td>
</tr>
</table>

### A WebFlux service: the trace follows the Reactor pipeline

A WebFlux controller or router handler returning a `Mono`/`Flux` runs its operators on whichever thread emits; by
default Reactor restores the trace only in `handle`/`tap`, so the log lines in `map`, `doOnNext`, … carry no trace id.
Where Spring Boot reads `spring.reactor.context-propagation` (Boot 3.2+, Boot 4 with `spring-boot-reactor`) logast sets
it to `auto`; otherwise it calls the hook first in `main`. The method itself stays as it is: a scope around a `Mono`
would end before the pipeline runs. *Scenario `expected-reactive`, `E2eApplication.java` (the property: the same
scenario with Boot's `ReactorAutoConfiguration` on the classpath).*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
@GetMapping("/{pledgeId}")
public Mono<String> show(@PathVariable String pledgeId) {
    return lookup.find(pledgeId);
}
```

```java
public static void main(String[] args) {
    SpringApplication.run(E2eApplication.class, args);
}
```

</td>
<td>

```java
@GetMapping("/{pledgeId}")
public Mono<String> show(@PathVariable String pledgeId) {
    return lookup.find(pledgeId);
}
```

```java
public static void main(String[] args) {
    Hooks.enableAutomaticContextPropagation();
    SpringApplication.run(E2eApplication.class, args);
}
```

```yaml
# or, where Spring Boot reads it:
spring:
  reactor:
    context-propagation: auto
```

</td>
</tr>
</table>

## Metrics

### A failure that is logged and handled is also counted, through a generated catalog

Call sites call a member of the package's metrics catalog and never spell out a metric name.
*`e2e/before` → `e2e/expected`, `PledgeSender.java` and the generated `PledgeMetrics.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
try {
    send(pledgeId);
    return true;
} catch (IllegalStateException e) {
    log.warn("Pledge resend failed [pledgeId={}]", pledgeId, e);
    return false;
}
```

</td>
<td>

```java
try {
    send(pledgeId);
    return true;
} catch (IllegalStateException e) {
    log.warn("Pledge resend failed", e);
    PledgeMetrics.pledgeResendFailed(); // logast:metric review, confidence: medium
    return false;
}
```

```java
// PledgeMetrics.java (generated)
/** pledge.resend{outcome="failed"} +1. */
public static void pledgeResendFailed() {
    Outcomes.count("pledge.resend", "failed");
}
```

</td>
</tr>
</table>

### Queries for what the service now measures and logs

`logast queries` turns the counters, observations and MDC keys into queries to paste (#106).
*`e2e/expected-decided/.logast/queries.md`, with `queries.splunk.index: shop-logs`.*

The counter of the failed resend above, in Prometheus and New Relic:

```promql
sum by (outcome) (increase(pledge_resend_total{application="e2e-shop"}[1h]))
```

```sql
FROM Metric SELECT sum(pledge.resend) WHERE service.name = 'e2e-shop' FACET outcome TIMESERIES 1 hour
```

Everything logged for one pledge in Splunk, across requests and jobs (the field names follow the service's log
format, here `logstash`):

```spl
index=shop-logs "service.name"="e2e-shop" pledgeId="<pledgeId>"
| sort _time
| table _time traceId level message
```

## Errors

### A lost cause is restored

The new exception now carries the original one, so its stack trace survives.
*`e2e/before` → `e2e/expected`, `PaymentClient.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
} catch (IllegalStateException e) {
    throw new PaymentException(
        "refund failed: " + e.getMessage());
}
```

</td>
<td>

```java
} catch (IllegalStateException e) {
    throw new PaymentException(
        "refund failed: " + e.getMessage(), e);
}
```

</td>
</tr>
</table>

### A catch-all `@ExceptionHandler` logs the exception

Spring logs nothing for a handled exception, so an unexpected failure answered with a 500 left no stack trace.
*Scenario `expected-errors-pack`, `ParcelErrors.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
@RestControllerAdvice
class ParcelErrors {
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }
```

</td>
<td>

```java
@Slf4j
@RestControllerAdvice
class ParcelErrors {
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Request failed unexpectedly", e); // logast:wording
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
    }
```

</td>
</tr>
</table>

### WebFlux error responses carry the trace id

A WebFlux-only service had no way to get the trace id into its `ProblemDetail` responses. The errors pack switches on
logging-support's `ReactiveProblemTraceId` (in vendor mode it copies that class into `<app package>.logging`), an
`ErrorResponse.Interceptor` that adds `"traceId"` to every RFC 9457 response WebFlux renders.
*Scenario `expected-webflux-errors`, `application.yml`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```yaml
spring:
  application:
    name: e2e-shop
```

</td>
<td>

```yaml
spring:
  application:
    name: e2e-shop
logging-support:
  problem-trace-id:
    enabled: true
```

```json
{ "status": 404, "title": "Not Found",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736" }
```

</td>
</tr>
</table>

### A fire-and-forget future no longer loses its exception

A `CompletableFuture` whose result nobody reads keeps its exception to itself; nothing is ever logged.
*`e2e/before` → `e2e/expected`, `QuoteController.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
String result = productId.strip();
CompletableFuture.runAsync(() -> System.out.println(result));
return result;
```

</td>
<td>

```java
String result = productId.strip();
CompletableFuture.runAsync(() -> System.out.println(result))
        .whenComplete((result2, error) -> {
            if (error != null) {
                log.error("Quote controller async work failed", error); // logast:wording
            }
        });
return result;
```

</td>
</tr>
</table>

## Resilience

### HTTP clients and JDBC queries get timeouts

Spring Boot sets none: a slow dependency holds a thread (and a connection) indefinitely. The values come from the
policy. *`e2e/before` → `e2e/expected`, `application.yml`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```yaml
spring:
  application:
    name: e2e-shop
```

</td>
<td>

```yaml
spring:
  application:
    name: e2e-shop
  jdbc:
    template:
      query-timeout: 5m
  http:
    clients:
      connect-timeout: 5s
      read-timeout: 30s
```

</td>
</tr>
</table>

### `@Retryable` and `@ConcurrencyLimit` are switched on

Spring Framework 7's resilience annotations do nothing without `@EnableResilientMethods`.
*Scenario `expected-spring-resilience`, `CarrierClient.java` and `E2eApplication.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
// CarrierClient
@Retryable(maxRetries = 2)
public String track(String parcelId) {
// …

// E2eApplication
@SpringBootApplication
public class E2eApplication {
```

</td>
<td>

```java
// CarrierClient (its settings move into application.yml,
// see "Retry settings move into configuration")
@Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}",
        delayString = "${resilience.retry.defaults.delay}")
public String track(String parcelId) {
// …

// E2eApplication
@EnableResilientMethods
@EnableConfigurationProperties(RetrySettings.class)
@SpringBootApplication
public class E2eApplication {
```

</td>
</tr>
</table>

### Retry settings move into configuration

Attempts and delays written into `@Retryable` move into `application.yml`. Usages with the same settings share one
block. A usage with settings of its own keeps them in its own block, and `.logast/retry.yaml` decides which usages get
one. A generated `RetrySettings` record binds the blocks.
*Scenario `expected-retry-settings`, `ParcelClient.java`, `ParcelGateway.java` and `application.yml`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
// ParcelClient (Spring Framework 7)
@Retryable(maxRetries = 2, delay = 500)
public String track(String parcelId) {
// …
@Retryable(value = IOException.class, maxRetries = 5,
        delay = 2, multiplier = 2.0, timeUnit = TimeUnit.SECONDS)
public String label(String parcelId) throws IOException {

// ParcelGateway (Spring Retry)
@Retryable(retryFor = IllegalStateException.class, maxAttempts = 4,
        backoff = @Backoff(delay = 200, multiplier = 1.5))
public void send(String parcelId) {
```

</td>
<td>

```java
// ParcelClient
@Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}",
        delayString = "${resilience.retry.defaults.delay}")
public String track(String parcelId) {
// …
@Retryable(value = IOException.class, timeUnit = TimeUnit.SECONDS,
        maxRetriesString = "${resilience.retry.usages.parcel-client.label.max-retries}",
        delayString = "${resilience.retry.usages.parcel-client.label.delay}",
        multiplierString = "${resilience.retry.usages.parcel-client.label.multiplier}")
public String label(String parcelId) throws IOException {

// ParcelGateway
@Retryable(retryFor = IllegalStateException.class,
        maxAttemptsExpression = "#{${resilience.retry.spring-retry-defaults.max-attempts}}",
        backoff = @Backoff(
                delayExpression = "#{${resilience.retry.spring-retry-defaults.delay}}",
                multiplierExpression = "#{${resilience.retry.spring-retry-defaults.multiplier}}"))
public void send(String parcelId) {
```

```yaml
# application.yml
resilience:
  retry:
    defaults:
      max-retries: 2
      delay: 500ms
    spring-retry-defaults:
      max-attempts: 4
      delay: 200
      multiplier: 1.5
    usages:
      parcel-client:
        label:
          max-retries: 5
          delay: 2000ms
          multiplier: 2.0
```

(logast writes each annotation on one line; wrapped here to fit.)

</td>
</tr>
</table>

## Audit

### Audit records get their own logger

Through the `audit` logger they can be routed and kept apart from the application log.
*`e2e/before` → `e2e/expected`, `Audited.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
public interface Audited {
    Logger AUDIT_LOG = LoggerFactory.getLogger(Audited.class);

    default void audit(String pledgeId) {
        AUDIT_LOG.info("Pledge audited [pledgeId={}]", pledgeId);
    }
}
```

</td>
<td>

```java
public interface Audited {
    Logger AUDIT_LOG = LoggerFactory.getLogger("audit");

    default void audit(String pledgeId) {
        AUDIT_LOG.info("Pledge audited");
    }
}
```

</td>
</tr>
</table>

### When logast can't decide: an audit record that does not say who did it

Which user to record is not something logast can know, so the code stays as it is and `FINDINGS.txt` says what to
decide and when it counts as done. *`e2e/expected/FINDINGS.txt` (wrapped; its `path` and `why` lines left out),
`PledgeService.java`.*

<table>
<tr><th>Code (unchanged)</th><th>What logast reports</th></tr>
<tr>
<td>

```java
/** Audited only when the delete went through: a failed or rejected attempt leaves no record. */
public void delete(String pledgeId) {
    repository.close(pledgeId);
    audit.record("pledge.delete", pledgeId, "done");
}
```

</td>
<td>

```text
ARI-dacf62  medium  REPORT-ONLY  AUDIT_RECORD_INCOMPLETE
  src/main/java/com/example/e2e/pledge/PledgeService.java:26
  PledgeService.delete
  The audit record record(…) in PledgeService.delete
  does not say who did it
  note: Pass the acting user (from the security context:
  SecurityContextHolder / the authenticated principal),
  or let the audit method read it itself. A record
  without them proves nothing.
  done when: an argument comes from the security context
  (a Principal / Authentication parameter,
  @AuthenticationPrincipal, SecurityContextHolder), or the
  audit method reads it itself
```

</td>
</tr>
</table>

The full rule list is in [rules.md](rules.md); each pack page explains its rules in detail.
