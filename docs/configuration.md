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
  keyStyle: dotted              # how MDC and [k=v] keys are spelled: dotted (order.id), camel (orderId),
                                #   pascal (OrderId), snake (order_id), keep. Applies to vocabulary keys too.
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
```

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
`// logast:ignore RULE_NAME` on the line or the line above.

---

## Older keys that still work

`glossary: { terms, synonyms }` and `mdc.keys` are the earlier, flat form of `vocabulary`. They are read and
merged into it; new files should use `vocabulary`.
