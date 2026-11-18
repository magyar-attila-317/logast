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
reported as done. *`e2e/before` → `e2e/expected`, `CouponCleanupJob.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
@Scheduled(cron = "0 0 2 * * *")
@Transactional
public void cleanup() {
    for (String couponId : repository.findOpen()) {
        log.info("Closing stale coupon {}", couponId);
        repository.close(couponId);
    }
}
```

</td>
<td>

```java
@Scheduled(cron = "0 0 2 * * *")
@Transactional
public void cleanup() {
    log.info("Coupon cleanup started"); // logast:wording
    var couponIds = repository.findOpen();
    for (String couponId : couponIds) {
        log.debug("Closing stale coupon [couponId={}]", couponId);
        repository.close(couponId);
    }
    AfterCommit.log(() -> log.info("Coupon cleanup completed [couponCount={}]", couponIds.size())); // logast:wording
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
already logs the exception; otherwise it reports it. *`e2e/before` → `e2e/expected`, `CouponLookup.java`.*

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
        log.error("Coupon reload failed", e);
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

Every line of the operation carries `couponId`, including those of the code it calls; the messages stop repeating
it. *`e2e/before` → `e2e/expected`, `CouponService.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
log.info("Coupon redemption started [couponId={}]",
        request.code());
sender.send(request.code());
log.info("Coupon redemption completed [couponId={}, tableId={}]",
        request.code(), request.tableId());
```

</td>
<td>

```java
try (var mdc = MdcScope.of("couponId", request.code())) {
    log.info("Coupon redemption started");
    sender.send(request.code());
    log.info("Coupon redemption completed [tableId={}]",
            request.tableId());
}
```

</td>
</tr>
</table>

### The scope opens at the entrypoint; code further down drops the id from its messages

*`e2e/before` → `e2e/expected`, `CouponController.java` and `CouponService.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
// CouponController
@PostMapping("/coupons/{couponId}/publish")
public void publish(@PathVariable String couponId) {
    coupons.publish(couponId);
}

// CouponService
public void publish(String couponId) {
    log.info("Publishing coupon {}", couponId);
    sender.send(couponId);
    repository.close(couponId);
    log.info("Coupon {} published", couponId);
}
```

</td>
<td>

```java
// CouponController
@PostMapping("/coupons/{couponId}/publish")
public void publish(@PathVariable String couponId) {
    try (var mdc = MdcScope.of("couponId", couponId)) {
        coupons.publish(couponId);
    }
}

// CouponService
public void publish(String couponId) {
    log.info("Publishing coupon");
    sender.send(couponId);
    repository.close(couponId);
    log.info("Coupon published");
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

A bean Redis calls through `new MessageListenerAdapter(relay, "onCoupon")` ran without a trace and without the coupon
id in MDC: Spring Data Redis observes Redis commands, not the listener container.
*Scenario `expected-relay-entrypoints`, `CouponRelay.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
public void onCoupon(String couponId) {
    relayLog.record(couponId);
}
```

</td>
<td>

```java
public void onCoupon(String couponId) {
    ObservedContext.entrypoint(RelayMetrics.COUPON_RELAY_ON_COUPON)
            .mdc("couponId", couponId)
            .debugOnSuccess()
            .run(() -> {
                relayLog.record(couponId);
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
@GetMapping("/{couponId}")
public Mono<String> show(@PathVariable String couponId) {
    return lookup.find(couponId);
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
@GetMapping("/{couponId}")
public Mono<String> show(@PathVariable String couponId) {
    return lookup.find(couponId);
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
*`e2e/before` → `e2e/expected`, `CouponSender.java` and the generated `CouponMetrics.java`.*

<table>
<tr><th>Before</th><th>After <code>logast apply</code></th></tr>
<tr>
<td>

```java
try {
    send(couponId);
    return true;
} catch (IllegalStateException e) {
    log.warn("Coupon resend failed [couponId={}]", couponId, e);
    return false;
}
```

</td>
<td>

```java
try {
    send(couponId);
    return true;
} catch (IllegalStateException e) {
    log.warn("Coupon resend failed", e);
    CouponMetrics.couponResendFailed(); // logast:metric review, confidence: medium
    return false;
}
```

```java
// CouponMetrics.java (generated)
/** coupon.resend{outcome="failed"} +1. */
public static void couponResendFailed() {
    Outcomes.count("coupon.resend", "failed");
}
```

</td>
</tr>
</table>

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
// CarrierClient (unchanged)
@Retryable(maxRetries = 2)
public String track(String parcelId) {
// …

// E2eApplication
@EnableResilientMethods
@SpringBootApplication
public class E2eApplication {
```

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

    default void audit(String couponId) {
        AUDIT_LOG.info("Coupon audited [couponId={}]", couponId);
    }
}
```

</td>
<td>

```java
public interface Audited {
    Logger AUDIT_LOG = LoggerFactory.getLogger("audit");

    default void audit(String couponId) {
        AUDIT_LOG.info("Coupon audited");
    }
}
```

</td>
</tr>
</table>

### When logast can't decide: an audit record that does not say who did it

Which user to record is not something logast can know, so the code stays as it is and `FINDINGS.txt` says what to
decide and when it counts as done. *`e2e/expected/FINDINGS.txt` (wrapped; its `path` and `why` lines left out),
`CouponService.java`.*

<table>
<tr><th>Code (unchanged)</th><th>What logast reports</th></tr>
<tr>
<td>

```java
/** Audited only when the delete went through: a failed or rejected attempt leaves no record. */
public void delete(String couponId) {
    repository.close(couponId);
    audit.record("coupon.delete", couponId, "done");
}
```

</td>
<td>

```text
ARI-dacf62  medium  REPORT-ONLY  AUDIT_RECORD_INCOMPLETE
  src/main/java/com/example/e2e/coupon/CouponService.java:26
  CouponService.delete
  The audit record record(…) in CouponService.delete
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
