# resilience pack

What turns a slow or failing dependency into an outage: missing timeouts, unbounded pools and waits, retries that
never run, rollouts that cut work off.

- **On by default** (every pack is: leave `resilience` out of `packs:` in `logast.yaml` to switch it off).
  `logast scan . --pack resilience` runs it alone.
- **Settings:** `resilience.*` in [configuration.md](../configuration.md#resilience-pack-resilience)
  (timeouts written by the AUTO fixes, `singleInstance`).
- It uses the same entrypoint and call-path analysis as the other packs, so a finding says *which request* is affected.

Legend: [rules.md](../rules.md#how-to-read-the-rule-tables).

## Rules

| rule | severity | finds | fix |
|---|---|---|---|
| HTTP_CLIENT_NO_TIMEOUT | high | Spring HTTP clients with no connect/read timeout (Boot sets none); JDK `HttpClient` without `connectTimeout`; clients the property does not reach: `RestClient.create()`, `new RestTemplate()`, `WebClient.create()` / the static `WebClient.builder()` (no auto-configuration), Apache `HttpClients.createDefault()` / `custom()` without a `RequestConfig` / `ConnectionConfig` (no response timeout by default). Feign and OkHttp are not reported: they default to 10s connect and 60s / 10s read | AUTO: `spring.http.clients.connect-timeout` / `read-timeout` (policy `resilience.*`, default 5s / 30s) for clients built from the injected `RestClient.Builder` / `RestTemplateBuilder` / `WebClient.Builder`; on Spring Boot 3.5 `spring.http.client.*` and, for WebClient, `spring.http.reactiveclient.*` (3.4: only the former; earlier: none, reported); report for the clients the property does not reach |
| DB_NO_TIMEOUT | high | JdbcTemplate / NamedParameterJdbcTemplate / JdbcClient without a query timeout (Spring Boot sets none; the driver's default is usually none); JPA (`@Entity`, `EntityManager`, `JpaRepository`) without `jakarta.persistence.query.timeout`; a JdbcTemplate created with `new` (or `JdbcClient.create(dataSource)`), which the property does not reach; `@Transactional` without a transaction timeout | AUTO: `spring.jdbc.template.query-timeout` and `spring.jpa.properties.jakarta.persistence.query.timeout` (policy `resilience.dbQueryTimeout`, default 5m: stops "forever" without cutting off batch queries); report for hand-made templates (inject Boot's, or `setQueryTimeout`) and for `spring.transaction.default-timeout` (it applies to every transaction) |
| REMOTE_CALL_IN_TRANSACTION | medium | a blocking HTTP call (RestTemplate, RestClient, `HttpClient.send`, a Feign / `@HttpExchange` interface) reached on the same thread from inside a `@Transactional` method: the database connection waits for the network | report, with the path (once per outermost transaction) |
| SCHEDULED_WITHOUT_LOCK | medium | `@Scheduled` jobs without a lock (ShedLock's `@SchedulerLock`): with more than one instance, each runs on every instance at the same time | report, one finding listing all of them; `resilience.singleInstance: true` in logast.yaml for services that never run more than once |
| KAFKA_NO_ERROR_HANDLER | medium | listeners on the default handling: 9 immediate retries, then the record is skipped | report: `DefaultErrorHandler` + `DeadLetterPublishingRecoverer` + backoff |
| RABBIT_NO_ERROR_HANDLER | medium | `@RabbitListener` on Spring Boot's defaults: a message whose listener throws is rejected and requeued, so one that always fails is redelivered without end. Not with `spring.rabbitmq.listener.<type>.retry.enabled: true` or `default-requeue-rejected: false`, a container factory that sets the requeue / an advice chain, an `ackMode` of its own, or `AmqpRejectAndDontRequeueException` thrown in the listener's class | report: listener retries plus a dead-letter exchange, or no requeue |
| UNBOUNDED_EXECUTOR | medium | `newCachedThreadPool`, `newFixedThreadPool` (unbounded queue), `ThreadPoolTaskExecutor` without queue capacity; `@Async` on Spring Boot's auto-configured executor (8 threads, `spring.task.execution.pool.queue-capacity` defaults to unbounded) when the service declares no `Executor` bean / `AsyncConfigurer` and no virtual threads | report |
| SCHEDULER_SINGLE_THREAD | medium | two or more `@Scheduled` jobs on Spring Boot's auto-configured scheduler, which has one thread (`spring.task.scheduling.pool.size` defaults to 1): one slow run delays every other job. Not with a `TaskScheduler` bean of the service's own or virtual threads | report: `spring.task.scheduling.pool.size` (jobs then run concurrently: a decision) |
| RETRY_INACTIVE | high | `@Retryable` / `@ConcurrencyLimit` (Spring Framework 7) without `@EnableResilientMethods`, Spring Retry's `@Retryable` / `@CircuitBreaker` without `@EnableRetry`: the annotations are metadata only, the method runs once | AUTO: adds the enabling annotation to the `@SpringBootApplication` class (Spring Retry's only with AspectJ on the classpath); report otherwise |
| RETRY_IN_TRANSACTION | medium | a `@Retryable` `@Transactional` method (Spring Framework 7 or Spring Retry) called from another class inside a transaction: it joins that transaction, so its retries run in one already marked rollback-only and the caller's commit fails. Not with `REQUIRES_NEW` / `NESTED` / `NOT_SUPPORTED` | report: retry the method that starts the transaction, or `REQUIRES_NEW` |
| RETRY_SETTINGS_HARDCODED | low | `@Retryable` (Spring Framework 7 or Spring Retry) with its attempts and delays written into the annotation (literals or the library defaults): changing them needs a rebuild and a release | AUTO: the settings move into `application.yml` (`resilience.retry.*`), bound by a generated `RetrySettings` record, and the annotation reads them through placeholders. See [Retry settings](#retry-settings-in-configuration) |
| RETRY_RECOMMENDED | low | a method whose outgoing HTTP calls (RestClient, RestTemplate, WebClient, HTTP interface, OpenFeign) are all idempotent (GET / HEAD / PUT / DELETE / OPTIONS), reached from an entrypoint along a path where nothing retries. Never for POST / PATCH / an unknown method. See [Where retry is recommended](#where-retry-is-recommended) | decided in `.logast/decisions/retry.yaml` (`retry: yes` / `no`): yes adds `@Retryable` where the proxy sees the call (on the method other beans call when the HTTP call sits in a helper the class calls on itself; none: retry in code), from a request at most one quick retry inside its timeout, from a job or consumer a retry with backoff; its values move into `application.yml` |
| NESTED_RETRY | medium | a retried method (`@Retryable`, Resilience4j `@Retry`) that calls, on the same thread through another bean (up to 5 calls deep), another retried method or a client with its own retry (`retryWhen`, `RetryTemplate`): every outer attempt runs all inner attempts | report: retry at one layer only |
| RETRY_BYPASSED | high | a `@Retryable` (Spring Framework 7 or Spring Retry) or Resilience4j `@Retry` that never runs: on a private / static / final method, or on a method only its own class calls (self-invocation bypasses the Spring proxy; Spring Framework reference, "Understanding AOP Proxies") | report: put it on the method other beans call (named in the finding), or move the method into its own bean; noted at the usage in `retry.yaml` |
| ROW_BY_ROW_WRITE | medium | a loop over a query result that writes one row per item | report: a batch update / one set-based statement |
| SHUTDOWN_NOT_GRACEFUL | medium | a web service with `server.shutdown: immediate` (graceful shutdown is Spring Boot's default); `spring.lifecycle.timeout-per-shutdown-phase` (30s when not set) shorter than an HTTP client read timeout, `spring.mvc.async.request-timeout` or `spring.transaction.default-timeout` the configuration sets: a rollout cuts that work off. Only with a `@SpringBootApplication` | report |
| HEALTH_PROBE_MISCONFIGURED | medium | `management.endpoint.health.probes.enabled: false` (switches the liveness/readiness groups off, on Kubernetes too); a database, broker or other external system in the liveness group (every instance restarts while it is down). Only with a `@SpringBootApplication` | report |
| READINESS_WITHOUT_DATABASE | low | a service with a database (JDBC, JPA, `@Transactional`) and Actuator whose readiness group does not include `db` (Spring Boot adds no checks to it). The Spring Boot reference calls it a judgement call: a shared database takes every instance out of service at once | report (low): include it, or switch the rule off for the service |
| CACHE_UNBOUNDED | medium | `@EnableCaching` on Spring Boot's simple provider (no cache library, or `spring.cache.type: simple`: ConcurrentHashMaps that never evict), a `ConcurrentMapCacheManager` bean, Caffeine without `maximumSize` / `maximumWeight` | report |
| BLOCKING_WAIT_UNBOUNDED | medium | `future.get()`, `join()`, `latch.await()` without timeout on a request/message path (`orTimeout(..)` in the chain, or an earlier bounded wait such as `allOf(a, b).get(5, SECONDS)` on the same futures, counts as one) | report |

## Where retry is recommended

A blanket "retry every external call" does harm: a repeated POST can book twice, retries at several layers multiply
(3 × 3 = 9 calls), retries past the caller's timeout only add load, and a service mesh, HTTP client or message
redelivery may already retry. So RETRY_RECOMMENDED is narrow:

- **Only idempotent calls.** GET, HEAD, PUT, DELETE and OPTIONS. A method that also makes a POST / PATCH call, or a
  call whose HTTP method logast cannot tell (`exchange(..)` with a `RequestEntity`, `@HttpExchange` without a method),
  is not reported.
- **Only where nothing retries yet.** The path from every entrypoint to the call is checked: `@Retryable` (Spring
  Framework 7, Spring Retry), Spring Retry's `@CircuitBreaker`, Resilience4j `@Retry`, a `RetryTemplate` /
  `RetryOperations` or Spring Cloud CircuitBreaker call, `retry` / `retryWhen` on the Mono/Flux, or a Feign `Retryer`
  bean count as retry. Kafka and RabbitMQ listeners count too: their containers redeliver a failed message.
- **Worded by caller.** From an HTTP request: at most one retry with a short backoff, inside the caller's timeout. From
  a scheduled job, a batch or a message consumer: a retry with backoff, its settings in `resilience.retry.defaults`
  (see below).

**POST and PATCH (no finding).** A retry is safe only when the server can recognise a repeat: send an idempotency key
(`Idempotency-Key` header) that the server stores with the result, or retry only failures where the request surely
never arrived (connect refused / connect timeout), not read timeouts or 5xx responses.

## Retry settings in configuration

RETRY_SETTINGS_HARDCODED moves the attempts and delays of every `@Retryable` into `application.yml`, so they can be
changed per environment and without a rebuild.

- **Shared by default.** Usages of one kind read one block: `resilience.retry.defaults` (Spring Framework 7) and
  `resilience.retry.spring-retry-defaults` (Spring Retry). Its values are the settings most usages have (a tie: the
  first in the code). The library defaults fill in what an annotation leaves out (Framework: 3 retries, 1000 ms delay;
  Spring Retry: 3 attempts, 1000 ms). Once the block is in `application.yml`, its values there are the shared ones.
- **Nothing changes silently.** A usage whose settings differ from the shared ones gets a block of its own,
  `resilience.retry.usages.<class>.<method>` (kebab case), with its current values. Sharing would change how it
  retries, so it is kept and the apply output names it.
- **Per usage on request.** `.logast/decisions/retry.yaml` lists every usage (see [configuration.md](../configuration.md#logastdecisionsretryyaml-retry-settings-per-usage)).
  `specific: true` gives it a block of its own on the next `apply`, starting from the shared values (or from
  `values:` given there). `false` points it back to the shared block. Its own block stays in `application.yml`,
  unused, so no value you set is lost.
- **The annotation reads the settings.**
  - Spring Framework 7: `maxRetriesString`, `delayString`, `jitterString`, `multiplierString`, `maxDelayString` and
    `timeoutString` take `${...}` placeholders and SpEL. The javadoc of each attribute says: "This supports
    Spring-style "${...}" placeholders as well as SpEL expressions" (`org.springframework.resilience.annotation.Retryable`,
    spring-context 7.0). Durations are written with a unit (`500ms`), which overrides the annotation's `timeUnit`.
  - Spring Retry 2.x: `maxAttemptsExpression` and `@Backoff(delayExpression, maxDelayExpression, multiplierExpression)`.
    The javadoc says "Use `#{...}` for one-time evaluation during initialization". `AnnotationAwareRetryOperationsInterceptor`
    resolves `${...}` in them first, so `#{${key}}` reads the property once at startup. The values are numbers: delays
    in plain milliseconds.
- **Typed and in one place.** `RetrySettings` (`@ConfigurationProperties("resilience.retry")`, a record in the package
  of the `@SpringBootApplication` class) binds every block. It is registered with
  `@EnableConfigurationProperties(RetrySettings.class)` on the application class. If an `@EnableConfigurationProperties`
  is already there, the class is added to it. With `@ConfigurationPropertiesScan` nothing is added. It needs Spring
  Boot 3 or later (record binding). Earlier versions get a report.
- **Left alone:** class lists (`includes`, `retryFor`, `value`), predicates, `random`, an annotation on a class,
  overloaded methods (they would share a block), a setting that is not a literal (a constant, or a placeholder or
  expression of the team's own), and a `timeUnit` below milliseconds. Existing keys in `application.yml` are never
  overwritten.
