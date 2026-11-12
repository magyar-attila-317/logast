# Vocabulary and policy layers

The standard is a **policy**: which words logs use, which MDC keys exist and where their values come from, how
lifecycle events are phrased, which levels mean what, which rule packs run. It is business-specific, so it lives
in your company, not in logast, and teams can extend or (visibly) override it.


## Start from the code

Don't write the vocabulary from scratch: `logast vocabulary <root>` harvests it (MDC keys, `[k=v]` keys in log
messages, id-like variables), grouped per term with counts, roles (`approvedByUserId` is a role of `user`, another
field, not an alias) and hints for likely duplicates. `logast init` writes the same harvest into a new `logast.yaml`.
Re-run it after editing: it lists only what the policy doesn't cover yet.

## Layers

```
built-in defaults        logast-defaults.yaml (inside logast): phrasing, level rules, common PII, packs
  └─ company             e.g. restaurant-company.yaml, published once (git raw URL / Nexus)
       └─ areas          inside any layer: per-package meanings and required MDC keys
            └─ service   <service>/logast.yaml  — extends: [<company url or path>]
```

Merge rules, from the bottom layer up:

| what | behaviour |
|---|---|
| objects (`vocabulary.order`, `logging`) | merged deeply |
| lists (`pii`, `aliases`, `clientErrors`) | appended, duplicates dropped |
| `packs`, `mdc.ambientKeys` | replaced (they are selections) |
| scalars (`logging.swallowedLevel`) | later layer wins; recorded as an override when it replaces a non-default value |
| `override: true` on an object | replaces the whole object; always recorded |

`logast policy <root>` prints the merged result, and `scan` lists every override at the end, so deviations from
the company standard stay visible without blocking anyone.

## Vocabulary entries

```yaml
vocabulary:
  order:
    aliases: [purchase]                  # "Placed purchase" -> "Placed order"; new text using "purchase" is rejected
    mdc:
      key: order.id                      # the MDC key
      aliases: [orderId, orderUuid]      # old key names: MDC.put("orderId", ..) is rewritten to "order.id"
      sources:                           # where logast finds the value, to set MDC / add [order.id=..]
        params: [orderId, orderUuid]
        types: { com.acme.Order: "getUuid()" }
      cardinality: high                  # ids: never a metric tag (metrics pack)
    logKey: order.id                     # name inside "Text [k=v]" (defaults to mdc.key)
```

What each part drives:

| entry | effect |
|---|---|
| `aliases` | GLOSSARY_VIOLATION rewrites existing messages; fill validation rejects new ones |
| `mdc.key` + `sources` | MISSING_DOMAIN_MDC adds `MdcScope.of("order.id", orderId)` at entrypoints; values already in MDC are dropped from messages |
| `mdc.key` (any method) | REPEATED_LOG_VALUE: logged by `mdc.extractRepeated` (default 2, `0` = off) statements of one method → one `MdcScope` there |
| `mdc.aliases` | MDC_KEY_ALIAS renames keys in `MDC.put` / `MdcScope.of`; `[orderId={}]` becomes `[order.id={}]` |
| `logKey` | the key name in `[k=v]` (LOG_KEY_STYLE) |
| key spelling | `logging.keyStyle`: `dotted` (default, `order.id`), `camel` (`orderId`), `pascal` (`OrderId`), `snake` (`order_id`), `keep`. Applies to every MDC and `[k=v]` key, vocabulary keys included: under `camel`, a vocabulary `mdc.key: order.id` is used as `orderId` and `order.id` becomes an alias that MDC_KEY_ALIAS migrates. Ambient keys (`traceId`, `spanId`) are never renamed. |

## Metric tags

`metrics.tags` is the vocabulary for metric tag keys: `aliases` are renamed to the key (METRIC_TAG_KEY), `values`
(optional) lists the allowed literal values (METRIC_TAG_VALUE). Ids never belong in tags: a vocabulary MDC key with
`cardinality: high` used as a tag is METRIC_HIGH_CARDINALITY_TAG.

```yaml
metrics:
  events: true          # BUSINESS_EVENT_UNMEASURED on/off
  tags:
    outcome: { aliases: [result, success], values: [success, failure, rejected] }
    channel: { aliases: [source, origin] }
```

## Areas: when a word means something else

```yaml
areas:
  kitchen:
    packages: [com.acme.kitchen]
    vocabulary:
      ticket: { aliases: [chit, docket] }   # "ticket" = the kitchen's copy of an order
    requiredMdc: [tenant.id]                # every kitchen log line must name the restaurant
```

- Area vocabulary wins inside its packages, so "order" can be an alias there without touching the global term.
- `requiredMdc` drives AREA_MDC_MISSING. It reports logging methods in the area that some path reaches without
  the key.

## Phrasing

Built-in events: `started`, `completed`, `failed`, `skipped`, `retrying`. Each has a canonical form and variants:

| existing message | becomes |
|---|---|
| `Starting reconciliation` | `Reconciliation started` |
| `Failed to charge card [order.id={}]` | `Charge card failed [order.id={}]` |
| `Error while syncing menu` | `Syncing menu failed` |
| `KDS_PRINT: could not build slip` | `KDS_PRINT: Build slip failed` (leading tags are kept) |

New text has to match the event the tool asks for: an entry log must read `… started`, and a swallowed
exception's log `… failed[, detail]`. Add your own events, or extend the variants, in any layer:

```yaml
phrasing:
  events:
    received:
      canonical: "{subject} received"
      variants: ["got {subject}", "incoming {subject}"]
```

## Levels

`levels.clientErrors` lists exception types that are expected outcomes (validation, auth, not-found). Logging them
at ERROR is a LOG_LEVEL finding, and the fix downgrades them to `clientErrorLevel` (WARN). `noInfoInLoops`
(opt-in) downgrades per-collection-element INFO logs to DEBUG. Retry and polling loops (`while`, `for(;;)`) are
never counted.

## Log volume

logast never guesses traffic.
- **LOG_IN_LOOP** works from the code alone. It flags an INFO line logged once per collection element on the normal
  path. WARN/ERROR lines and catch blocks are left alone, since those are per failure.
- **LOG_VOLUME** needs real rates. Run `logast traffic <root>`: it writes `.logast/traffic.yaml` with every
  entrypoint listed and the rates empty. Fill in what you know; rerunning keeps your values and adds new
  entrypoints. A filled-in file looks like this:

```yaml
entrypoints:                     # calls per day; labels as `logast explain <root>` prints them
  "POST /orders": 120000
  "GET /orders/{orderId}": 2500000
  "@Scheduled ReconciliationJob.reconcile": 1440
  "@KafkaListener shipment-acks": 400000
```

A Prometheus query result saved as JSON works as is, for example
`sum by (method, uri) (increase(http_server_requests_seconds_count[1d]))` (the `method` and `uri` labels become
`"POST /orders"`). Each statement gets lines/day = the sum of the rates of the entrypoints that reach it. INFO+ lines
above `levels.volumeBudget` (default 100,000 per day) are reported with the busiest entrypoints. Catch blocks are
excluded, because they run only on failure. `scan` lists traffic labels that match no entrypoint, so a typo doesn't
quietly mean "no data".

## Packs

`packs: [logging, tracing]` is the default. `metrics` is opt-in. `scan --pack metrics` runs only the metrics rules.
`logast rules` lists every rule with its packs.

## Worked example

See `examples/restaurant-company.yaml` (company layer for a restaurant POS domain) and
`examples/restaurant-service.yaml` (a service that extends it, adds an area and overrides one term).
