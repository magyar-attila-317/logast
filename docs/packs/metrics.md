# metrics pack

Meter names and tags that don't explode the metrics backend, business events that are measured, and every meter
tagged with the service and version that wrote it.

- **On by default** (every pack is: leave `metrics` out of `packs:` in `logast.yaml` to switch it off).
  `logast scan . --pack metrics` runs it alone.
- **Settings:** `metrics.*` in [configuration.md](../configuration.md#metrics-pack-metrics) (`events`, `defaults`,
  `amounts`, `catalog`, `tags`).
- Decisions a person or an LLM makes go into `.logast/decisions/metrics.yaml` ([below](#deciding-what-to-count-metricsyaml)).

Legend: [rules.md](../rules.md#how-to-read-the-rule-tables).

## Rules

| rule | severity | finds | fix |
|---|---|---|---|
| BUSINESS_EVENT_UNMEASURED | medium | a business event (a method logging "X started" / "X completed", or any wording in `phrasing.events` such as "Starting X" / "X done"; "Processing X" counts as the start when X's completion is logged in the same method) with no count, duration or error rate | AUTO: an Observation around it (timer and span) |
| METRIC_OUTCOME_UNMEASURED | low | an outcome the logs report that no metric counts | report; AUTO for the `metrics.defaults` categories and once its `metrics.yaml` entry is decided |
| METRIC_CATALOG | low | a metric or observation name written at the call site (outcome tags too: `Outcomes.tag("skipped")` → `OrderMetrics.tagSkipped()`) | AUTO: moved into a catalog class, which registers its counters at 0 when the application starts (a series exists before the first event: 0 instead of "no data", and "0 for an hour" can alert) |
| OBSERVED_ANNOTATION_INACTIVE | high | `@Observed` / `@Timed` / `@Counted` that Spring Boot does not process (also in tracing) | AUTO: switches them on |
| METRIC_NAMING | low | a meter name that is not lowercase, dot-separated vocabulary terms | AUTO: renamed |
| METRIC_TAG_KEY | low | a tag key that is an alias in the tag vocabulary (`metrics.tags`) | AUTO: renamed |
| METRIC_TAG_VALUE | low | a literal tag value outside the allowed `values` | report |
| METRIC_TAG_KEYS_MISMATCH | high | one meter name registered with different tag keys (Prometheus drops the later ones) | report |
| METRIC_HIGH_CARDINALITY_TAG | high | an id or PII as a tag value: one time series per value | report |
| METRIC_GAUGE_WEAK_REF | high | a gauge on an object nothing else references (reads NaN after GC) | report |
| METRIC_REGISTERED_PER_CALL | low | a meter looked up or built for every item of a loop | report |
| METRIC_DIRECT_REGISTRY | low | a hand-made timer in an entrypoint the framework already times | report |
| SETUP_SERVICE_IDENTITY | medium | meters without `application` / `version` tags (also logging, tracing) | AUTO |

## The metrics map

Start with the map: what is measured and what is not, rich enough to choose metrics without reading code.
```
$ logast metrics .
Export: none
  No exporter on the classpath: meters are collected but never leave the application.
Identity
  spring.application.name: pos-backend
  spring.application.version: (not set: manifest Implementation-Version, if any)
Measured without code: 661 entrypoints
  631 by Spring MVC, e.g. http.server.requests{method="POST", uri="/api/orders/{id}/close"}
  30 by Spring scheduling, e.g. tasks.scheduled.execution{code.namespace="…", code.function="nightly"}
Entrypoints no metric covers: 184
Outcomes logged but not counted: 264  failed 165, skipped 27, completed 12, …
  src/…/BillService.java:88  BillService.close
    INFO "Bill request closed"  outcome=closed  frequency unknown: POST /bills/{id}/close has no traffic.yaml rate
```
`--format json` has every entry, with the exact metric and tag values to query and, for each uncounted outcome, a
ready snippet: an `outcome` tag on the timer that is already running, or a counter. With the metrics pack on, the
same outcomes are METRIC_OUTCOME_UNMEASURED findings (REPORT-ONLY): logast does not decide which business metrics
matter, the LLM or a person picks.

## Annotations

`@Timed`, `@Counted` and `@Observed` measure only while Spring Boot processes them: the same switch as for tracing,
`management.observations.annotations.enabled: true` plus aspectjweaver (Boot reference, Observability, "Micrometer
Observation Annotations support"; MetricsAspectsAutoConfiguration). The metrics pack switches them on by itself
(OBSERVED_ANNOTATION_INACTIVE, shared with the tracing pack: one order when both run); while they are off, an annotated
method counts as unmeasured and its business event gets an observation.

## Outcomes counted by default

Some outcomes are counted by default, without a decision, best effort like the logging fixes (`metrics.defaults`
in logast.yaml, all four on; `[]` switches them off; a metrics.yaml decision always wins):

| category | logged outcome | counted |
|---|---|---|
| `failures` | "X failed" | `x{outcome=failed}` +1 |
| `jobs` | completed / failed / skipped in a `@Scheduled` job, batch runner or message/event listener, or the methods it calls up to two levels down (HTTP is covered by `http.server.requests`) | `x{outcome=…}` +1 |
| `skips` | "X skipped" | `x{outcome=skipped}` +1 |
| `db-writes` | a database write's `[rows={}]` line (also at DEBUG) | `x{outcome=…}` +1, `x.rows` grows by the row count |

Each such line is marked for review with how sure logast is of the metric name:
```java
log.info("Order shipment skipped");
OrderMetrics.orderShipmentSkipped(); // logast:metric review, confidence: medium
```
A counting line goes right after the log line's statement, so a log line that does not run there and then is left to
you (it waits in metrics.yaml with a `_problem`): inside a `return`/`throw`, in a lambda on another thread
(`runAsync(() -> …)`), or under a condition in a lambda (`whenComplete((r, e) -> { if (e != null) log… })`).

`high`: every word of the name is a vocabulary term; `medium`: the name comes from the message; `low`: the message was
generated by logast (`// logast:wording`), so the name comes from class or method names. Remove the comment once the
name is right.

## Deciding what to count (`metrics.yaml`)

Decisions go into `.logast/decisions/metrics.yaml`, kept up to date by `scan` and `apply` like `messages.yaml`:
```yaml
OUM-ed9954:
  count: counter                    # counter | tag | skip   (empty = not decided)
  name: settlement.export
  outcome: completed
  _message: INFO "Settlement export completed"
  _frequency: low
  _tag: not available here …
```
`logast apply . --auto` then writes one line after the log line: `Outcomes.count("settlement.export", "completed")` (a
counter `settlement.export{outcome="completed"}`, created once and cached), `Outcomes.tag("completed")` (`outcome` on the
timer already running, offered only where one is known to run), or for `skip` a `// logast:ignore
METRIC_OUTCOME_UNMEASURED` comment, so the decision lives in the code. `Outcomes` is in logging-support (copied in
vendor mode when Micrometer is present).

### Amounts

Where the line also logs a number, the entry offers it (`_amounts`), and `amount` counts it too:
```yaml
OUM-3f2a10:
  count: counter
  name: orders
  outcome: shipped
  amount: items                     # one of _amounts; empty = count how often only
  as: ""                            # total | per-run; empty = metrics.amounts from logast.yaml (default total)
  _amounts:
    - items
  _message: INFO "Orders shipped"
  …
```
- `total`: `Outcomes.count("orders", "shipped", "items", orders.size())`: `orders{outcome}` by 1 and a counter
  `orders.items{outcome}` that grows by the number (totals, throughput).
- `per-run`: `Outcomes.record(…)`: `orders{outcome}` by 1 and the number as one sample of a distribution summary
  `orders.items{outcome}` (average, maximum, percentiles where the registry has them).

Only quantities are offered: numbers whose key names a count or size, not an id, number, code, date part or status
(`batchNumber`, `supplierId` are left out). The logged expression is evaluated again in the counting line, so it must be
free of side effects (a variable, a field, `size()`/`length()`/a getter or record accessor); otherwise the entry gets
a `_problem` saying so, and `apply` reports it. `metrics.amounts: off` in logast.yaml stops offering amounts.

## Metric catalog

Metric names then move into one place (METRIC_CATALOG, AUTO): call sites call a member of a catalog class and never
name a metric or an observation.
```java
log.info("Orders shipped [items={}]", orders.size());
OrderMetrics.ordersShipped(orders.size());              // was Outcomes.count("orders", "shipped", "items", …)
ObservedContext.entrypoint(JobMetrics.TALLY)      // was ObservedContext.entrypoint("tally")

// logast:metrics-catalog
public final class OrderMetrics {
    /** orders{outcome="shipped"} +1; orders.items{outcome="shipped"} grows by items. */
    public static void ordersShipped(Number items) {
        Outcomes.count("orders", "shipped", "items", items);
    }
}
```
`metrics.catalog` in logast.yaml picks where they live: `per-package` (default; one `<Package>Metrics` class per
package that counts, next to its code; a metric used in several packages stays in the package of its first use),
`single` (all in `metrics.catalogClass`, default `<app package>.metrics.BusinessMetrics`) or `off` (names stay at the
call sites). Rename or document members freely: logast recognises its catalogs by the `// logast:metrics-catalog`
comment, keeps what is there, and adds new members at the end. Counting lines written by hand
(`Outcomes.count("…", "…")`) move into the catalog too.

## Service identity

Every log line and meter also says which service and version wrote it (SETUP_SERVICE_IDENTITY, AUTO): logast adds
`spring.application.name`/`version` when missing (env vars still win at runtime; set `logging.serviceName` /
`logging.serviceVersion` in logast.yaml to your platform's expressions, default `${APPLICATION_VERSION:unknown}`),
`application`/`version` tags on every meter, `service.name`/`version` in Logstash JSON (ECS and GELF take them
from `spring.application.*`), and `service.version` on the OTel resource.

## Business events

With the pack on (the default), a business event that is logged but not measured gets an
Observation, which gives a timer (count, duration, errors) and a span in one:
```java
public void refund(String orderId, long amountCents) {
    ObservedContext.entrypoint("refund")
            .quiet()                         // the method logs its own lines; this adds the timer and span
            .run(() -> {
                log.info("Refund started");
                …
                log.info("Refund completed");
            });
}
```
A business event is a method logging "X started" / "X completed" (the phrasing rules), where X names a vocabulary
term or the method logs the full lifecycle. HTTP endpoints, `@Scheduled` methods and observed Kafka listeners are
already timed by Spring and left alone.

## Tags and meters

Tags get a vocabulary like MDC keys (aliases are renamed, `values` optionally restricts literal values):
```yaml
metrics:
  tags:
    outcome: { aliases: [result, success, succeeded], values: [success, failure] }
```
It also catches what silently breaks metrics: one meter name registered with different tag keys (Prometheus drops the
later ones), gauges on objects nothing else references (they read NaN after GC), meters built per loop item, ids or
PII as tags. `logast metrics .` lists every meter, and the fleet report compares them across services: same name with
different types/tags, and similar names (`orders.placed` vs `order_placed`).
