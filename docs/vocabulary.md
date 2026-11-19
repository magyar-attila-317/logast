# Vocabulary and policy layers

The standard is a **policy**: which words logs use, which MDC keys exist and where their values come from, how
lifecycle events are phrased, which levels mean what, which rule packs run. It is business-specific, so it lives
in your company, not in logast, and teams can extend or (visibly) override it.


## Start from the code

Don't write the vocabulary from scratch: `logast vocabulary <root>` harvests it (MDC keys, `[k=v]` keys in log
messages, id-like variables), and `logast init` writes the same harvest into a new `logast.yaml`. The harvest only
collects. Every key becomes an active term of its own, named and spelled exactly as in the code
(`acmeWeek: { mdc: { key: acmeWeek } }`), with its counts (mdc / log / declared, files). Nothing is merged, renamed or
converted to `logging.keyStyle`: without edits every key stays its own term. Grouping is up to you; the harvest only
hints in comments:

- `same value as order_id?`: spellings of one identifier (`orderId`, `order_id`, `order.id`, `orderUuid`). An id
  and a number (`supplierId`, `supplierNumber`) are never hinted as the same.
- `a kind of userId?`: a role (`openedByUserId`), when that key exists too.
- Identifiers that are only declared, never logged, are listed commented out; remove the `#` to add one.

Organising it looks like this:

```yaml
vocabulary:
  order:                     # the business word; was the harvested term orderId
    aliases: [purchase]      # banned words: "purchase" in a message is rewritten to "order"
    mdc:
      key: orderId           # the key logs carry
      aliases: [order_id, orderUuid]   # the same value spelled differently: merged into this term
  supplier:                  # supplierId and supplierNumber are two different values: two terms
    mdc: { key: supplierId }
  supplierNumber:
    mdc: { key: supplierNumber }
  user:
    mdc: { key: userId }
  approver:                  # a role: another field of the same kind as userId, not an alias of it
    mdc: { key: approverUserId }
```

Delete the keys that are not business terms (technical ones such as `retryCount`). Re-run `logast vocabulary` after
editing: it lists only what the policy doesn't cover yet.

## One vocabulary across services

Services drift apart: `orderId` in one, `order_id` or `order.id` in another. The central vocabulary collects every
service's keys in one place outside the repos, so merging them is a decision on evidence, not on similar names.

| file | holds | edited by |
|---|---|---|
| `~/.logast/vocabulary-worksheet.yaml` | every key of every service with its evidence, and your decisions | you decide; logast fills in the evidence |
| `~/.logast/company.yaml` | between its `# >>> logast` and `# <<< logast` lines: **generated** from the decided terms (canonical key, aliases, sources, grouped by area where decided); outside them: company settings of your own, kept on every run | logast (generated part), you (the rest) |
| `<service>/logast.yaml` | `extends: [~/.logast/company.yaml]` and the terms only that service has | rarely |

In a [named fleet](fleet.md) the same files live in the fleet folder: `logast fleet <name> --vocabulary` builds the
worksheet from the members' snapshots, and members extend its company.yaml through `fleet: <name>`. The flow with a
folder of your own:

1. `logast vocabulary --central ~/.logast services/billing services/shipping …` harvests every service (MDC keys,
   `[k=v]` keys and `addKeyValue` keys that name an identifier) into the worksheet.
2. Decide in the worksheet, per key: `term: <name>` publishes the key as a term; `same_as: <key>` makes it an alias of
   that key's term; `local: true` keeps it in its service. `owner: <team>` is optional (company.yaml has no field
   for it, so it is written there as a comment). `area: <name>` next to `term:` groups the term under an area.
3. Run the same command again: it keeps your decisions, refreshes the evidence and writes `company.yaml` with the
   decided terms only. Undecided keys never go there.
4. Services `extends:` company.yaml. `logast fleet --vocabulary --central ~/.logast <svc>…` shows per service what
   apply would rename, before anyone extends it; `scan` then says which of the service's own vocabulary entries
   company.yaml now defines, and `logast vocabulary --prune <svc>` removes them from its logast.yaml.
5. `logast apply` renames the aliases in the code (MDC_KEY_ALIAS): `MDC.put("order_id", …)` becomes `"orderId"`.

A worksheet entry, after a decision:

```yaml
keys:
  orderId:
    term: order                      # your decision: kept across runs
    owner: "order team"
    services: [billing]              # from here on: rewritten on each run
    types: ["Long"]
    similar:
      - "order_id: same type Long, source Order.id(); differs: services billing vs shipping"
    evidence:
      - {service: "billing", at: "src/main/java/com/example/billing/InvoiceDesk.java:11", use: mdc, value: "String.valueOf(order.id())", from: "Order.id()", type: "Long"}
```

- **Evidence**: per service and place, how the key is used (`mdc`, `kv` for `[k={}]` in a message, `addKeyValue`),
  the value as written, the accessor it comes from and the value's type, read from declarations in the service's own
  sources (a parameter, a local, a record component, an accessor's return type). `String.valueOf(x)` counts as `x`.
- **similar** only points out keys with the same words in another style (`orderId`, `order_id`, `order.id`), with
  what is the same and what differs (type, source, services). Nothing is merged until you write `same_as`. An id and
  a number (`orderId`, `orderNo`) are never pointed out as the same.
- **warning**: one name with different types (`customerId` a Long in one service, a String in another) is possibly
  not the same value; give one of them another key if so.
- **suggest**: `local: true?` for a key only one service uses; `same_as: <key>?` when a decided look-alike has the same
  type.
- **The summary at the top** lists, per run: the keys to decide (and which are new), decided terms no service uses
  any more, keys still used against a decision (an alias still in the code, a `local` key in two services), and
  decisions that cannot be published (a `same_as` to a key without a term, a cycle, one term decided twice).

### Grouping terms by area

A large vocabulary stays readable when its terms are grouped by [area](#areas-when-a-word-means-something-else).
Write `area: <name>` next to `term:`, and optionally give the area its packages in the worksheet's `areas` section:

```yaml
areas:
  orders:
    packages: [com.example.billing, com.example.shipping]   # your decision: published with the area
    terms: [order]                                          # from here on: rewritten on each run
  bookkeeping:
    known_in:                                               # what the services' own logast.yaml say
      - {service: "ledger", packages: [com.example.ledger], requiredMdc: [entryId]}
keys:
  orderId:
    term: order
    area: orders
```

company.yaml then has the term under `areas.orders.vocabulary` (with the packages), and terms without an area stay in
the top-level `vocabulary`:

```yaml
areas:
  orders:
    packages: [com.example.billing, com.example.shipping]
    vocabulary:
      order:   # owner: order team
        mdc:
          key: orderId
          aliases: [order_id]
```

- The worksheet's `areas` section lists every area the services' own logast.yaml define (`known_in`: packages,
  vocabulary, requiredMdc), the areas you decided, and the terms published under each. The summary names an area
  with terms but no packages anywhere: its words in messages then apply nowhere.
- An area's packages can also come from the service: a service's `areas.orders.packages` merges with company.yaml's
  (the usual layering), and so do terms the service adds to the area.
- An area term's MDC key holds in the whole service, not only in the area's packages (the MDC travels with the
  thread): MDC_KEY_ALIAS renames its aliases everywhere, and `--prune` counts an area term as covering a service's
  entry that has only an MDC part. The area scopes the term's words in messages (`aliases`).
- `area:` without `term:` is listed under "decisions to fix"; a `same_as` key follows its term's area.

For a team, keep company.yaml in a small repository, change it by pull request, and let services pin it:
`extends: [https://…/company.yaml#sha256=…]`. A `mdc.sources.types` entry of company.yaml that a service does not have
is expected (it lists every service's types) and is not reported by `policy --check`; the service's own entries are.

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

`logast explain policy <root>` prints the merged result, and `scan` lists every override at the end, so deviations from
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
| `mdc.key` + `sources` | MISSING_DOMAIN_MDC adds `MdcScope.of("order.id", orderId)` at entrypoints; values already in MDC are dropped from messages; a parameter named like a source, of a configured type, or a request / message object with a getter or record accessor named like a source (`request.getOrderId()` of a protobuf request, `cmd.orderId()`: a public no-argument method of the parameter's own type returning text, a number, a UUID or an enum; not for application-event listeners) |
| `mdc.key` (any method) | REPEATED_LOG_VALUE: logged by `mdc.extractRepeated` (default 2, `0` = off) statements of one method → one `MdcScope` there |
| `mdc.aliases` | MDC_KEY_ALIAS renames keys in `MDC.put` / `MdcScope.of`; `[orderId={}]` becomes `[order.id={}]` |
| `logKey` | the key name in `[k=v]` (LOG_KEY_STYLE) |
| key spelling | `logging.keyStyle`: `camel` (default, `orderId`), `dotted` (`order.id`), `pascal` (`OrderId`), `snake` (`order_id`), `keep`. Applies to every MDC and `[k=v]` key, vocabulary keys included: under `camel`, a vocabulary `mdc.key: order.id` is used as `orderId` and `order.id` becomes an alias that MDC_KEY_ALIAS migrates. Ambient keys (`traceId`, `spanId`) are never renamed. |

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
- An area term's `mdc` (key, aliases, sources) counts in the whole service, because MDC keys are not bound to a
  package; the global vocabulary wins when both name the same MDC spelling.
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
- **LOG_VOLUME** needs real rates. Run `logast traffic <root>`: it writes `.logast/decisions/traffic.yaml` with every
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

Every pack runs by default (`packs: [logging, tracing, metrics, resilience, errors, audit]`); leave one out to switch it off. `scan --pack metrics`
runs only the metrics rules. [rules.md](rules.md) describes every pack and rule.
`logast explain rules` lists every rule with its packs.

## Worked example

See `examples/restaurant-company.yaml` (company layer for a restaurant POS domain) and
`examples/restaurant-service.yaml` (a service that extends it, adds an area and overrides one term).
