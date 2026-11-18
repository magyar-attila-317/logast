# Configuring logast (`logast.yaml`)

`logast.yaml` sits in the root of a service. It tells logast how *your* services should log, trace and measure.
You only write what differs from the defaults; everything here is optional.

```bash
logast policy .        # prints the effective configuration (all layers merged) and what was overridden
```

## How the configuration is put together

logast reads up to four layers and merges them, top to bottom:

```
built-in defaults           phrasing, levels, PII words, packs: logging + tracing
└─ company layer            your business words, shared by every service    (extends:)
   └─ area layer            a group of services, e.g. "kitchen"             (extends:)
      └─ logast.yaml        this service
```

- **Lists add up**: a service's `pii` list is added to the company's.
- **Objects merge**: you can change one setting of a term without repeating the rest.
- **`override: true` replaces** an object instead of merging it. Every override is listed by `logast policy .`
  and in the fleet report, so they stay visible.

```yaml
extends:
  - https://git.example.com/platform/observability/raw/main/company.yaml   # a URL, or
  - ../shared/kitchen.yaml                                                # a file relative to this one
```

---

## The settings, one by one

Defaults are shown in the examples. Anything you leave out keeps its default.

### `packs`: which checks run

```yaml
packs: [logging, tracing]
```

| pack | checks |
|---|---|
| `logging` | message format and wording, exceptions, MDC context, what goes into a log line (default) |
| `tracing` | traces across threads, HTTP calls, Kafka and job launches (default) |
| `metrics` | meter names and tags, business events without metrics |
| `resilience` | timeouts, Kafka error handling, thread pools, unbounded waits |
| `errors` | error responses: leaked details, unmapped exceptions, ProblemDetail |
| `audit` | actions that must leave an audit record |

Every rule of every pack, with what it finds and how it is fixed: [rules.md](rules.md).

`logast scan . --pack metrics` runs a pack for one scan without changing the file. `packs:` replaces the list
from earlier layers (it does not add to it).

### `vocabulary`: your business words

The most important section. One entry per business concept.

```yaml
vocabulary:
  order:                                  # the word your logs use
    aliases: [purchase, ordr]             # words that mean the same thing; rewritten to "order" in messages
    mdc:
      key: orderId                        # the MDC key that carries the order's id
      aliases: [order_id, orderUuid]      # old MDC key names; renamed to orderId
      sources:                            # where logast finds the value in code
        params: [orderId, orderUuid]      #   method parameters with these names
        types:
          com.example.order.Order: "getId()"   #   or this accessor on this type
      cardinality: high                   # high (default) = an id: never a metric tag. low = a small set of values
    logKey: orderId                       # the name inside "Message [orderId=…]" (default: mdc.key)
```

What logast does with it:
- puts `orderId` into MDC where a flow starts (HTTP endpoint, listener, job), and removes it from messages
  below that point, because every line already carries it;
- renames old key names and banned words;
- refuses new message text that uses a banned word.

Don't write this from scratch: `logast vocabulary .` lists the ids your code already uses, grouped and counted.
See [vocabulary.md](vocabulary.md) for areas and worked examples.

### `areas`: where a word means something else

```yaml
areas:
  kitchen:
    packages: [com.example.kitchen]       # classes in these packages belong to the area
    vocabulary:
      ticket: { aliases: [chit, docket] } # "ticket" means a kitchen ticket here
    requiredMdc: [tenantId]               # every log line in the area must have these MDC keys
```

### `logging`: message format and wording

```yaml
logging:
  keyStyle: camel               # how MDC and [k=v] keys are spelled: camel (orderId), dotted (order.id),
                                #   pascal (OrderId), snake (order_id), keep. Applies to vocabulary keys too.
  serviceName: "${SERVICE_NAME:shop}"          # written to spring.application.name when missing (default: the project name)
  serviceVersion: "${APPLICATION_VERSION:unknown}"   # written to spring.application.version when missing
  structuredFormat: ecs         # JSON log format logast writes to application.yml (logging.structured.format.console)
                                #   when the service sets none: ecs, logstash, gelf; none = leave the config alone.
                                #   A service that already uses logstash-logback-encoder (or GELF) keeps that format.
  rewriteExistingMessages: true # false = keep the wording of existing messages; only fix structure
                                #   (concatenation, lost exceptions, banned words). Good for legacy services.
  loggerName: log               # the field name used when logast adds a logger
  entryLevel: info              # level of the "X started" line logast adds to silent entrypoints
  minEntrypointLevel: info      # an entrypoint with nothing logged at this level or above is "silent"
  swallowedLevel: warn          # level for a log line added to a catch block that swallowed an exception
  protectedMessages:            # messages alerts or dashboards depend on (regex): never reworded, only reported
    - "^ALERT:"
  vagueMessages:                # wording to avoid (regex, added to the built-in list); reported as MESSAGE_VAGUE
    - "^handled\\.?$"
```

### `phrasing`: one way to say lifecycle events

Built in: `X started`, `X completed`, `X failed`, `X skipped`, `X retrying`, plus common variants ("Finished X",
"Unable to X") that are rewritten to them. Add your own variants:

```yaml
phrasing:
  events:
    completed:
      variants: ["{subject} wrapped up"]
```

### `levels`: log levels

```yaml
levels:
  clientErrors:                 # exceptions caused by the caller (added to the built-in list)
    - com.example.BusinessRuleViolationException
  clientErrorLevel: warn        # client errors are never logged as ERROR
  infoInLoops: size             # INFO once per loop item:
                                #   size   = fine when the loop is small, DEBUG when it runs over query/stream data
                                #            (database rows, file lines, Kafka batches), reported when unknown
                                #   never  = DEBUG unless the loop is known to be small; always = no loop checks
  boundedLoopMax: 20            # a loop over at most this many items counts as small
  volumeBudget: 100000          # log lines per day one statement may produce (with traffic numbers)
```

Tell logast how big a loop is with a comment right above it (it can't see your data):
```java
// logast:loop-size 10        (or: small) INFO per item is fine here
// logast:loop-size large     one line per item would flood: DEBUG, with a summary after the loop
for (Store store : stores) { ... }
```

### `mdc`

```yaml
mdc:
  ambientKeys: [traceId, spanId] # keys tracing maintains: never set by hand, never renamed
  extractRepeated: 2             # an id logged by this many lines of one method goes into MDC once (0 = off)
```

### `pii`: personal data

```yaml
pii: [guestName, guestPhone]     # added to the built-in list (email, phone, iban, cardNumber, password, …)
```

Values with these names are removed from log lines and refused as metric tags.

### `entrypoints`

```yaml
entrypoints:
  extraAnnotations: [com.example.InboxHandler]   # your own annotations that start work (like @KafkaListener)
  includeMain: false                             # treat main() as an entrypoint (command-line tools)
```

HTTP endpoints, `@Scheduled`, Kafka/JMS/Rabbit listeners, event listeners, WebSocket handlers and
`CommandLineRunner`s are found automatically.

HTTP endpoints are found the way Spring MVC finds them (#51):
- a `@Controller` / `@RestController` class, also through your own annotation that carries one (one to three levels
  of meta-annotation, in source or in a jar);
- the mapping on the method, or on the method it overrides in an interface or superclass: in source, in a library jar
  (read from the class file) or in generated sources;
- `@GetMapping`, `@PostMapping`, …, `@RequestMapping`, or your own annotation meta-annotated with `@RequestMapping`
  (its own `value`/`path`, the `@AliasFor` attribute, wins over the meta-annotation's);
- the class-level `@RequestMapping` from the controller (or its meta-annotation), else the nearest supertype;
- paths from literals, `static final String` constants and their `+` concatenation.

A mapping with several paths (`@GetMapping({"/a", "/b"})`) stays one entrypoint (one handler: one entry MDC scope, one
entry line), named after its first path. Every path is a line of its own in `.logast/decisions/traffic.yaml` (the handler's rate
is their sum) and `audit.actions.http` matches any of them; the `http.server.requests` query names the first.

Generated sources (`build/generated/**`, `target/generated-sources/**` of each module: OpenAPI interfaces, annotation
processor output) are compiled together with the sources so their types and annotations resolve, but they are never
analysed, reported or edited. Generate them before running logast (e.g. `./gradlew openApiGenerate compileJava`);
without them a controller implementing a generated interface has no HTTP entrypoints. A generated file declaring a
class the sources also declare is ignored.

### `support`: the runtime helper library

```yaml
support:
  mode: dependency                               # dependency = add the logging-support library
                                                 # vendor     = copy its classes into the service (no library)
  coordinates: dev.logast:logging-support:0.1.0  # where to get the library (dependency mode)
  vendorPackage: com.example.shop.logging        # where to copy the classes (vendor mode; default <app>.logging)
```

Use `vendor` until the library is in your company repository.

### `metrics` (pack `metrics`)

```yaml
metrics:
  events: true                  # business events without a metric get an Observation (timer + count + span)
  defaults: [failures, jobs, skips, db-writes]   # logged outcomes counted without a metrics.yaml decision; [] = none
  amounts: total                # a number on an outcome line counted too: total (counter), per-run (distribution), off
  catalog: per-package          # metric names in one place: per-package, single (all in catalogClass), off
  # catalogClass: com.example.metrics.BusinessMetrics   # for single (default: <app package>.metrics.BusinessMetrics)
  tags:                         # tag vocabulary
    outcome:
      aliases: [result, success] # renamed to "outcome"
      values: [success, failure] # optional: allowed literal values
```

### `resilience` (pack `resilience`)

```yaml
resilience:
  httpConnectTimeout: 5s        # written to spring.http.clients.connect-timeout when a service has none
  httpReadTimeout: 30s          # written to spring.http.clients.read-timeout
  dbQueryTimeout: 5m            # written to spring.jdbc.template.query-timeout and the JPA query timeout
  singleInstance: false         # true: the service never runs more than once, @Scheduled jobs need no lock
```

What each setting does in detail: [the metrics pack](packs/metrics.md) and [the resilience pack](packs/resilience.md).

#### `.logast/decisions/retry.yaml`: every retry decision

(Before #108 this file was `.logast/retry.yaml`; the next command moves it.)

With the resilience pack, logast keeps `.logast/decisions/retry.yaml` up to date, the way `.logast/decisions/traffic.yaml`
lists the entrypoints. Keep it in git. It holds every retry decision of the service (#109):

```yaml
usages:                  # the existing retries (RETRY_SETTINGS_HARDCODED)
  ParcelClient.track:    # Spring Framework @Retryable, src/main/java/com/example/parcel/ParcelClient.java:11
    specific: true       # its own block: resilience.retry.usages.parcel-client.track.*
    values:              # optional: what the new block starts with (default: the shared values)
      max-retries: 5
  ParcelLedger.record:   # Spring Framework @Retryable, src/main/java/com/example/parcel/ParcelLedger.java:15
    # retries inside ParcelClient.track, which retries too: the attempts multiply (NESTED_RETRY); keep one of them
    specific: false      # the shared block: resilience.retry.defaults.*
candidates:              # idempotent HTTP calls nothing retries (RETRY_RECOMMENDED)
  ParcelClient.quote:    # src/…/ParcelClient.java:16, reached from GET /parcels/{id}/quote (request)
    retry: "yes"         # yes: one quick retry (maxRetries 1, delay 100 ms) · no
    specific: true       # optional, as under usages: taken over by the usage yes creates, in the same apply
    values:
      max-retries: 2
  ParcelClient.status:
    retry: "no"          # not recommended again
```

The next `logast apply` follows it:

- **usages:** empty means logast decides: the shared block, unless the usage's settings differ from it. Then the usage
  keeps them in a block of its own. Marking a usage `false` again points it back to the shared block; its own block
  stays in `application.yml`, unused.
- **candidates:** `yes` adds Spring Framework 7's `@Retryable` (from a request one quick retry, from a job or consumer
  retries with backoff), enables it where needed and moves its values into `application.yml`, in one apply; the
  method is then listed under `usages`. `no` stops the recommendation; the entry stays so it can be changed back.
  Say no when a gateway or service mesh already retries, or the remote side must not see the call twice.

Details: [the resilience pack](packs/resilience.md#retry-settings-in-configuration).

### `audit` (pack `audit`)

```yaml
audit:
  emitters:                     # calls that record an audit event (class, or class#method)
    - com.example.audit.AuditService
  actions:                      # entrypoints that must record one; any field matches
    - http: "DELETE **"         #   * = within a path segment, ** = across segments
    - http: "* /admin/**"
    - method: "(?i)(refund|export|grant).*"   # regex on the method name
    - term: refund              #   a vocabulary term in the endpoint or method name
```

With no `emitters`, any call on a type named `*Audit*`, or with an `*Audit*` argument (an `AuditAction` value, an
event implementing `AuditableDomainEvent`) counts.

### `reporting`: what scan writes besides the findings

```yaml
reporting:
  sourceComments: false   # true: add "// logast: RULE ID" beside findings that need an entry in messages.yaml or
                          #   metrics.yaml (a debugging aid; scan adds and removes them)
```

Independent of this setting, `scan` and `apply --write` with the logging pack write `.logast/reports/coverage.txt`: every log
call with what logast did with it (fixed, reported, ignored, skipped with the reason, checked). See "Every log call is
accounted for" in [commands](commands.md#every-log-call-is-accounted-for).

### `queries`: ready-made queries

What `logast queries` writes ([commands](commands.md#queries)).

```yaml
queries:
  backends: [prometheus, newrelic, splunk]   # default: all three
  splunk:
    index: app-logs        # the index the service logs to; without it there are no Splunk queries
    sourcetype: _json      # optional: added to every search
  prometheus:
    serviceLabel: application   # the label that names the service; default: application when
                                #   management.metrics.tags.application is set (logast sets it), else job
```

- **New Relic** queries assume Micrometer metrics sent over OTLP with the resource attribute `service.name`.
- **Splunk** queries assume the JSON fields are extracted (sourcetype `_json` or `KV_MODE=json`).

### `verify`

```yaml
verify:
  tasks: [test, intTest]        # Gradle tasks that run your tests (default: test)
  gradleDir: ../..              # run Gradle here, relative to the service (a subproject of a bigger build)
```

### `rules`: switch single rules off or change their severity

```yaml
rules:
  ENTRYPOINT_SILENT: { severity: low }
  LOG_IN_LOOP: { enabled: false }
```

`logast rules` lists every rule and its pack. The log-coverage rules (JOB_OUTCOME_MISSING, SKIP_UNLOGGED,
STATE_CHANGE_UNLOGGED, BATCH_SUMMARY_MISSING, DB_OUTCOME_UNLOGGED) add log lines; switch off the ones a service does not want. A single finding is silenced in code with
`// logast:ignore RULE_NAME` on the line or the line above (rule ids match exactly; list several separated by
spaces or commas), and in a whole Java file with `// logast:ignore-file RULE_NAME`.

---

## Older keys that still work

`glossary: { terms, synonyms }` and `mdc.keys` are the earlier, flat form of `vocabulary`. They are read and
merged into it; new files should use `vocabulary`.
