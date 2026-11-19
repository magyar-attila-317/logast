# Commands

Every command prints its options with `--help` (`logast scan --help`). This page explains what they are for, with
examples. Output samples are illustrations; counts and ids differ per service and version.

`logast --help` lists seven commands; everything else is a report logast writes on its own, a question under
`explain`, or an [advanced command](#advanced).

| command | what it does |
|---|---|
| `logast apply <svc> [--only ids] [--dry-run] [--force]` | applies every order that needs no text (`--only`: just these) on a scratch copy, rescanning between rounds, and writes the result (`--dry-run`: preview only). Refuses a git working tree with uncommitted changes unless `--force`. On a service without `logast.yaml` (or with one that holds only `fleet:` / `extends:`) it writes one first (what `init` writes, leaving to the fleet or extended layer what they set), on a Gradle build without a classpath file it reads one. Message texts come from `.logast/decisions/messages.yaml` (or `--fills <file>`); `--auto` and `--write` are accepted and are the default |
| `logast scan <svc>` | finds gaps, writes `.logast/reports/orders.json` and the [reports](#reports-written-automatically), prints the work orders |
| `logast review <svc> [--open]` | local page for the optional decisions (message texts, metrics, retries), dry run and apply |
| `logast verify <svc>` | runs the existing tests with log capture and checks what was really logged |
| `logast explain <svc> [topic]` | the call tree below the entrypoints, or one question: `mdc <key>`, `lifecycle`, `metrics`, `queries`, `policy`, `rules` ([below](#explain)) |
| `logast fleet create\|join\|leave\|list` | named fleets: a service joins once (`fleet: <name>` in its logast.yaml), then its scans and applies leave snapshots in the fleet folder ([fleet.md](fleet.md)) |
| `logast fleet <name> [--refresh] [--html] [--json] [--vocabulary]` | the fleet's overview from the snapshots: scores, open findings, trace breaks, one outcome under several metric names, vocabulary conflicts; `--vocabulary` writes the fleet's worksheet and company.yaml |
| `logast fleet <svc>…` | the earlier form: scans the given services and writes the report; calls whose trace stops between two services; one outcome counted under several metric names |
| `logast fleet --vocabulary [--central <dir>] <svc>…` | per service, the keys in the code the vocabulary makes aliases (what apply would rename); with `--central`, as if every service extended `<dir>/company.yaml` |
| `logast init [svc]` | optional: choose before the first apply (`logast.yaml`, `.gitignore`, classpath, traffic template; `--baseline` for CI). `apply` does the same setup itself when there is no `logast.yaml` |

Options most analysing commands share: `--policy <file>`, `--classpath-file <file>`, `--pack <packs>`,
`--traffic <file>`.

### The `.logast` folder

| folder | what is in it | in git? |
|---|---|---|
| `.logast/decisions/` | what a person decides, all YAML: `messages.yaml` (message texts), `metrics.yaml` (count, tag or skip a logged outcome), `traffic.yaml` (calls/day per entrypoint), `retry.yaml` (every retry decision: settings per usage, and yes / no for the calls logast recommends retrying). logast keeps the entries in step with the code and keeps what you wrote | yes |
| `.logast/reports/` | what logast writes for you to read: `orders.json`, `coverage.txt`, `metrics.md`, `queries.md`, `lifecycle.txt` ([written automatically](#reports-written-automatically)), `steps/`, `apply-trace.txt`, `last-apply.txt`, `verify.json` | no |
| `.logast/` | `classpath-*.txt` (machine input), `baseline.json` (CI), `README.md`, `shape/` | baseline and README |

A `.logast` from an earlier logast version (`fills.json`, `metrics.json`, `traffic.yaml`, `retry.yaml` and the reports
at its top) is moved into this layout by the next command that reads it: `fills.json` and `metrics.json` become
`decisions/messages.yaml` and `decisions/metrics.yaml` with the same entries, the rest moves as it is. One line says
what moved. If a file exists in both places, the new one is used and the old one stays, with a warning: nothing you
wrote is lost. `init`'s `.gitignore` entries and `.logast/README.md` are updated with it.

Output is coloured in a terminal, and long steps (`scan`, `apply`) show a live progress line: the current step, the
round, the number of fixes done and the elapsed time. In CI or a pipe each step is printed as a plain line instead.
`NO_COLOR=1` turns colours off, `LOGAST_COLOR=always|never` forces them.

## init

Optional: `apply` on a service without `logast.yaml` does the same setup with the defaults (every pack, the key style
and log format the code already uses, the keys it finds). Run `init` first to choose differently.

| option | what it does |
|---|---|
| `--extends <url\|file>` | build on your company's shared settings (comma-separated) |
| `--key-style <style>` | spelling of MDC and `[k=v]` keys: `dotted`, `camel`, `pascal`, `snake`. Without it: the style the code already uses most, else `camel` |
| `--keep-wording` | legacy service: fix structure, keep the wording of existing messages |
| `--support vendor` | copy the [starter](starter.md)'s classes into the service (the default unless the policy names a repository; see [platform.md](platform.md)) |
| `--pack logging,tracing,metrics` | which packs run (default: all six, `logging`, `tracing`, `metrics`, `resilience`, `errors`, `audit`) |
| `--baseline` | scan once and record today's findings for CI |
| `--no-classpath` / `--no-traffic` | skip those steps |
| `--force` | write a fresh `logast.yaml` over an existing one |

`init` is safe to re-run: it keeps the files you already edited. It does not create `messages.yaml` / `metrics.yaml`:
their entries belong to the code the first `apply` writes (generated lines, database and job outcome lines) and to
the wording it leaves, so they only make sense after it. Each step is also its own command:
`logast classpath .`, `logast traffic .`, `logast scan . --update-baseline`.

## scan

In a terminal, `scan` shows what needs a person, grouped (shortened from `e2e/expected-scan-human/SCAN.txt`):

```
$ logast scan .
logast · 53 files · 21 entrypoints · log coverage 42.9%

143 findings: 93 logast fixes, 5 need a message, 11 outcomes to count or skip, 34 need your decision
  logast fixes     message not in house format 41, business id missing from MDC 7, …
  messages         write in .logast/decisions/messages.yaml or `logast review .`: business id missing from MDC 3, …
  outcomes         decide in .logast/decisions/metrics.yaml (.logast/reports/metrics.md has the context): 11 logged but not counted

Your decisions (most important first; → what to do)
  high
    async work loses the log context (4)  ASYNC_CONTEXT_LOSS
      → Pass a context-propagating executor: ContextExecutors.wrap(executor) or a Spring TaskExecutor bean.
      edge/EdgeFlow.java:41        CompletableFuture.runAsync runs on another thread without MDC/trace context
      web/QuoteController.java:16  CompletableFuture.supplyAsync runs on another thread without MDC/trace context
      …
  medium
    wait without a timeout  BLOCKING_WAIT_UNBOUNDED
      → Wait with a bound: get(5, TimeUnit.SECONDS) / orTimeout(..) / await(5, SECONDS), and handle the TimeoutExcep…
      web/QuoteController.java:18  CompletableFuture.join() without a timeout on a request path: if the work n… ×2
  …

what to decide, and when it is done: logast scan . --rule ASYNC_CONTEXT_LOSS   ·   everything in full (for an LLM): --format compact
```

Each rule's → line is the first instruction of its findings' notes ("e.g." when they differ). `--rule X` (or
`--entrypoint`) shows every detail of those findings: the evidence, the whole suggestion, and when it counts as done. Piped or redirected (an LLM, CI, a file), `scan` prints that full listing for every order:

```
$ logast scan . --format compact
SWA-d5b58b  low  NEEDS TEXT  SWALLOWED_EXCEPTION  src/main/java/com/example/shop/service/DefaultOrderService.java:41
  PaymentException is caught and dropped without a log line in DefaultOrderService.place (documented as intentional)
  path: POST /orders → OrderController.create → DefaultOrderService.place
  + log.warn("{{MSG}}", order.id(){{ARGS}}, e);
  MSG: What failed and what happens next … | suffix keys: [order.id] | must read as '{subject} failed'
```

- Narrow the list: `--rule`, `--pack`, `--severity high`, `--entrypoint "POST /orders"`, `--limit`.
- Change the output: `--format human` (default in a terminal) `| compact` (default otherwise) `| summary | json | sarif | fills-template`.
- CI: `--baseline`, `--update-baseline`, `--since <git ref>`, `--fail-on <severity>` (see the README's CI section).
  The Gradle plugin runs the same scan and gate as `./gradlew logastScan` / `logastCheck`, and writes SARIF for PR
  annotations with `logastSarif` ([gradle-plugin.md](gradle-plugin.md)).
- `scan` also creates and updates `.logast/decisions/messages.yaml` and `.logast/decisions/metrics.yaml`: it keeps every entry you already
  have exactly as it is, adds entries for new findings and removes the ones that are done. Nothing is rewritten when
  nothing changed.

### What happens next

The end of `scan` and `apply` is for a person: it says what was done and groups what is left by what you may do about
it, not by rule. Nothing in it is required: every entry has a default. `scan --format summary` prints only that part,
plus the worst findings:

```
Applied earlier: 100 changes in 53 files (git diff to review)

Optional (67) — left as is, each keeps its default:
  21 log lines: a text of your own  → .logast/decisions/messages.yaml   (blank: the current text stays)
  12 outcomes: count, tag or skip   → .logast/decisions/metrics.yaml   (blank: no metric)
  34 decisions                      → logast scan .   (each says what to decide)
     mostly: vague message 6, incomplete audit record 5, async work loses the log context 4, …
  to decide: logast review (or edit .logast/decisions/*.yaml), then logast apply . again   (on this code: don't revert it)

Fix first (high):
  async work loses the log context   src/…/EdgeFlow.java:44          ASYNC_CONTEXT_LOSS
  async work loses the log context   src/…/QuoteController.java:18   ASYNC_CONTEXT_LOSS
  transactions without a timeout     src/…/application.yml:1         DB_NO_TIMEOUT
  (23 medium, 39 low: logast scan .)

Log lines: 85 — 51 fixed, 15 with a finding, 11 fine, 8 not checked (.logast/reports/coverage.txt)
Places that should log: 17 of 21 do (jobs 9/9, endpoints 8/12)
```

- **Applied earlier** appears when an `apply` changed files (`.logast/reports/last-apply.txt`).
- After `apply`, the same blocks read as sentences: see [apply](#apply).
- **Next** comes first in `scan` when logast still has fixes of its own to make: `logast apply .`.
- **Optional (N)** is what logast leaves to you, each with its default: message wording (`messages.yaml`; blank: the
  current or generated text stays), whether to count a logged outcome (`metrics.yaml`; blank: no metric), whether to
  retry a call (`retry.yaml`; blank: no retry), and decisions it only reports (the three most frequent kinds by name).
  One pointer covers them: `logast review` (or the yaml files), then `logast apply .` again. Reported decisions are
  done by changing the code.
- When nothing is pending and nothing is left to decide, it says `Nothing left`.
- **Fix first** lists the findings of the worst severity among those left to a person (up to 8), by a short name, with
  the rule id last; the rest are counted.
- **Also** holds the optional and follow-up checks (`logast verify .`, traffic rates).

The compact listing (the default) ends with the same blocks, without "Fix first" (the listing is the detail).

The log-coverage line is the share of places that should log (jobs, HTTP endpoints that change something, skips,
state changes, loops) and do; see [log coverage](packs/logging.md#log-coverage). It is also a column in the fleet
report.

### Every log call is accounted for

A line without a finding could mean "checked, fine" or "never looked at", so `scan` (and `apply`) also writes
`.logast/reports/coverage.txt`: one line per log call, in file/line order, with what logast did with it:

```
src/main/java/com/example/shop/service/CouponService.java:41   fixed    LOG_FORMAT FMT-e10ae3; reported MESSAGE_VAGUE MVG-53c6bf
src/main/java/com/example/shop/service/CouponService.java:44   checked  no finding
src/main/java/com/example/shop/service/GatewayCalls.java:10    skipped  protected message (logging.protectedMessages)
src/main/java/com/example/shop/service/GatewayCalls.java:14    ignored  LOG_FORMAT
src/main/java/com/example/shop/service/LegacyGateway.java:9    skipped  other logging API: java.util.logging
```

and one line at the end of the run's output (the skip reasons and every line's detail stay in the file):

```
Log lines: 85 — 51 fixed, 15 with a finding, 11 fine, 8 not checked (.logast/reports/coverage.txt)
```

| outcome | meaning |
|---|---|
| `fixed` | an `apply` changed it (rule and id). Later runs keep saying so while the file is unchanged since; the file's fingerprint is at the end of `coverage.txt` |
| `reported` | a finding sits on the line (rule and id, as in the scan output) |
| `ignored` | a `// logast:ignore RULE` comment silenced that rule there |
| `skipped` | a rule passed it over on purpose, with the reason it recorded where it decided: a protected message, a log helper's body, a message that is not a literal (a constant, built at runtime), a message that comes from an argument (`"{}"`), a fluent call (`log.atInfo()…`: format and level not checked), TRACE (arguments not checked), the logging-support classes copied into the service, or another logging API (Log4j2, java.util.logging, …: logast edits SLF4J only) |
| `checked` | the rules looked at it and found nothing |

The first outcome that applies is in the second column; the others follow in the detail. The number of lines always
equals the number of log calls logast found. It is the logging pack's report: a run without that pack (`--pack
tracing`, …) neither writes nor changes it, and a dry-run `apply` does not write it. Log calls in field initializers
and static blocks are not in it (logast analyses method and constructor bodies). Source markers
(`reporting.sourceComments: true`, see [configuration](configuration.md)) are an off-by-default debugging aid;
`coverage.txt` is the answer to "did logast look at this line?".

## apply

`apply` fixes what it can across all selected packs (best effort), refreshes the classpath when it changed a build
file, creates the files to fill (`.logast/decisions/messages.yaml`, `.logast/decisions/metrics.yaml`, `.logast/decisions/traffic.yaml`), and ends with
what is left, grouped by what you do about it ([what happens next](#what-happens-next)):

```
Set up: classpath refreshed for the new dependencies (84 entries), one more round with the refreshed classpath

✓ 104 changes in 53 files (41.2s)
  44 log lines rewritten in the standard format
  15 log lines added where something happened silently
  10 outcomes counted (a metric next to their log line)
  9 setup changes (dependencies, configuration, tracing)
  9 MDC keys tidied
  17 other changes
  support code copied into com/example/e2e/logging (16 files)
  review: git diff   (one diff per kind: .logast/reports/steps/)

Worth a look (optional) — the service works without these:
  21 log lines could say more, 12 outcomes could be counted → logast review
  34 things logast can't fix itself, e.g. vague message (6), incomplete audit record (5), async work loses the log context (4) → logast scan .

Log lines: 85 — 52 fixed, 15 with a finding, 10 fine, 8 not checked (.logast/reports/coverage.txt)

Also: fill calls/day in .logast/decisions/traffic.yaml (optional)   21 of 21 entrypoints have no rate: log levels and metrics frequency stay cautious there
Also: logast verify .   build and run the tests with log capture: does the result really log what it should?

reports: .logast/reports/ metrics.md queries.md lifecycle.txt (logast explain metrics|queries|lifecycle prints them)
```

- **Set up** appears only when this run set something up: `logast.yaml` written, the classpath read with Gradle, or
  refreshed after a build-file change. After a refresh, apply runs one more round with the new types; the summary
  counts both.
- **✓ N changes in M files**: one line, then what they did by kind (from the pipeline steps), largest first.
- **Worth a look** is optional: what `logast review` decides (wording, counting outcomes, retries), and what logast
  can't fix itself, with the most frequent kinds (`logast scan .` lists them).
- On a terminal, the run shows one progress line that updates in place (`migrating… step 3/8: missing log lines`);
  elsewhere (CI, pipes) only the summary is printed. Gradle's own output (classpath read or refreshed) is shown only
  when it fails.
- `--verbose` adds the applied order ids and the changed files; `.logast/reports/last-apply.txt` always has them.

One `apply` does everything automatic, and its result compiles: the service is done there. A second one is only for
what you decided: `logast review` (or the files in `.logast/decisions/`), then `logast apply .` again on the migrated
code. `logast scan .` shows the same without changing anything. Both also write the
[reports](#reports-written-automatically). `apply` records what it changed in
`.logast/reports/last-apply.txt`, so a later scan starts with "Applied earlier: 100 changes in 53 files".

- **Steps.** `apply` runs in steps (setup, structure, lines, mdc, messages, exceptions, levels, reports), each on the
  result of the previous one, and saves one diff per step in `.logast/reports/steps/` (`01-setup.diff`, `02-structure.diff`,
  …). They apply in order with `git apply`, so a whole kind of change can be reviewed on its own, or dropped: later
  steps build on earlier ones, so drop from the end, or rerun with `--until <step>`. Background:
  [pipeline-plan.md](pipeline-plan.md).
- **Checks.** Every run checks its result before writing (no key both in MDC and in a message, no nested scope, one
  started/completed per method, the result still compiles) and prints `CHECK FAILED file:line` if something breaks
  that.
- **Trace.** `.logast/reports/apply-trace.txt` records what each round applied and what its rescan brought up.
- **Coverage.** Unless `--dry-run`, `.logast/reports/coverage.txt` lists every log call of the result; the ones this run changed
  show as `fixed` ([every log call is accounted for](#every-log-call-is-accounted-for)).
- `--only <ids>` restricts it to some orders; `--format json` prints the result for tools.

## review

```bash
logast review . --open
```

Opens a local page (127.0.0.1, token-protected) for everything optional, in one place:

- one input per message; texts already in `.logast/decisions/messages.yaml` are filled in, and what you type is saved
  there. It shows the code, the path from the entrypoint, the required phrasing and the suffix keys. Blank: the
  current or generated text stays.
- the outcomes of `.logast/decisions/metrics.yaml` (count: counter, tag or skip, name and outcome; blank: no metric) and
  the retry candidates of `.logast/decisions/retry.yaml` (yes or no; blank: no retry). *Save decisions* writes them
  into those files, which stay the stored form.

*Dry run* shows the diff and any rejections; *Apply* writes (or run `logast apply .`).

## verify

```
$ logast verify .
verify: 2 log events from tests; 2 of 23 log statements exercised (8%)
RTL-dace69  high  REPORT-ONLY  RUNTIME_TRACE_LOST  …/notification/Mailer.java:12  Mailer.send
  No traceId here, although the same test had one on thread 'Test worker': the work moved to thread
  'pool-1-thread-1' without its trace
RNV-5ecdae  medium  REPORT-ONLY  RUNTIME_NULL_VALUE  …/notification/Mailer.java:12
  A logged value rendered as 'null'
```

It runs your existing tests (the build isn't changed; an init script adds the capture jar), records every log
event, and reports context that existed in a test and then got lost, plus values logged as `null`. The result goes
to `.logast/reports/verify.json`; `--no-run` analyses the captures of the previous run again.

By default it runs `./gradlew test`. Services with other test tasks set them in `logast.yaml` (or pass
`--tasks "test intTest bddTest"`):

```yaml
verify:
  tasks: [test, intTest, bddTest]   # or [check]
  gradleDir: ../..                  # optional: run Gradle from the repo root (service is a subproject)
```

## explain

`logast explain .` lists the entrypoints; `--entrypoint "POST /orders"` shows the call tree below one, with log, MDC
and async markers and the ids of the orders on each method (`--depth`, default 12). A topic after `explain` asks one
question instead:

| topic | what it prints |
|---|---|
| `explain mdc <key>` (or `explain mdc <svc> <key>`) | why an MDC key is scoped where it is: every method that logs it, the decision (scope, covered, in MDC, no scope, lifted) and the reason, plus the scopes already in the code |
| `explain lifecycle <svc>` | every flow's lifecycle lines: which exist, who owns the start and the outcome, what logast will add; implementation families that log unevenly. The same as `.logast/reports/lifecycle.txt` |
| `explain metrics <svc>` | the metrics map ([below](#metricsmd)). The same as `.logast/reports/metrics.md`; `--format json` has every entry with the exact metric and tag values, `--format table` the meters only |
| `explain queries <svc>` | the ready-made queries ([below](#queriesmd)). The same as `.logast/reports/queries.md` |
| `explain policy <svc> [--check]` | the effective policy after all layers, with overrides; `--check` (CI) checks it against the code instead: exit 0 fine, 1 warnings, 2 errors ([configuration](configuration.md#how-the-configuration-is-put-together)) |
| `explain rules` | every rule and its packs |

## fleet

```bash
logast fleet create payments-fleet                # once; --dir <folder> keeps it in a folder of your choice
logast fleet join payments-fleet                  # in each service: writes `fleet: payments-fleet` into logast.yaml
logast fleet payments-fleet                       # the overview from the members' last scans
logast fleet payments-fleet --vocabulary          # the fleet's vocabulary worksheet and company.yaml
logast fleet list                                 # the fleets this machine knows
logast fleet services/*/ --out fleet-report       # the earlier form: scan these roots, fleet-report/fleet.html
```

A named fleet is a folder with its members, one snapshot per service and its shared `company.yaml`; every `scan` and
`apply` of a member (and the Gradle plugin's tasks) updates the member's snapshot, and `logast fleet <name>` compares
them without rescanning (`--refresh` rescans, `--html` writes the page below, `--json`). Where fleets live
(`--fleets-dir`, `LOGAST_FLEETS`, `fleets.dir` in `~/.config/logast/config.yaml`, default
`~/.local/share/logast/fleets/`) and the whole workflow: [fleet.md](fleet.md). The comparison is the same in both forms:

A compliance report across services (HTML and a JSON snapshot; earlier snapshots in `--out` give the trend). It also
compares meters across services: the same name with different types or tags, and similar names
(`orders.placed` vs `order_placed`). `@file` reads the service roots from a file. `--classpath-file` gives every
service the same classpath file (default: each service's `.logast/classpath*.txt`).

It also compares the services with each other. Both sections are printed, and are in the HTML and in the JSON
snapshot (`links`):

- **Trace across services.** Each outgoing call of a service is matched against the entrypoints of the others:
  RestClient / WebClient (`client.get().uri(…)`), RestTemplate (`getForObject`, `exchange`, …), OpenFeign and HTTP
  interface methods against request mappings (method and path; `/orders/{id}` matches `/orders/42`, a URI template and a
  value the code does not know), and KafkaTemplate / RabbitTemplate / JmsTemplate sends against `@KafkaListener` /
  `@RabbitListener` / `@JmsListener` (topic, queue or routing key, destination). Constants, `@Value("${…}")` fields
  and placeholders resolve from the service's application.yml. A base URL from a property, a variable or the client's
  base URL is not known: such a call is matched on its path only, and the output says so. For every pair it lists the
  call site, the entrypoint and which side drops the trace, with the order of that service's scan that fixes it:
  - the caller: an HTTP client built by hand (`HTTP_CLIENT_UNTRACED` where it is built, in the same class or in a
    `@Bean` method of that client kind), OpenFeign without feign-micrometer, a KafkaTemplate without
    `spring.kafka.template.observation-enabled` (`KAFKA_OBSERVATION_DISABLED`), a RabbitTemplate without
    `spring.rabbitmq.template.observation-enabled` (no rule: set it by hand). Spring Boot observes the JmsTemplate it
    configures;
  - the callee: an entrypoint that is not observed (`ENTRYPOINT_NOT_OBSERVED`), a Kafka or RabbitMQ listener without
    listener observation (`KAFKA_OBSERVATION_DISABLED`, `RABBIT_OBSERVATION_DISABLED`).

  Pairs where the trace goes on are listed too. Calls inside one service are not compared. The caller side needs the
  tracing pack (its findings are the evidence).
- **One outcome, several metric names.** Log lines that report an outcome (as METRIC_OUTCOME_UNMEASURED recognises
  them: "Order shipped") and the meter that counts them: the next counting statement in the method (a counter, a
  counter field's `increment()`, `Outcomes.count` / `record`, a metrics catalog method) or `@Counted`. The same subject
  and outcome counted under different meters in different services is a candidate, with every log line, meter and
  place. The same name in every service is not. Nothing is merged: pick one name and rename.

```text
Trace across services: 5 calls reach another service, 2 drop the trace
  orders -> billing  POST /invoices
    call   src/main/java/com/example/orders/InvoiceClient.java:11  RestClient, InvoiceClient.request
    entry  src/main/java/com/example/billing/InvoiceController.java:20  POST /invoices, InvoiceController.create
    breaks at the caller (orders): RestClient in a field initializer: no traceparent header on outgoing calls
      fix: order HTC-c8498e (HTTP_CLIENT_UNTRACED) in orders: Build it in the constructor from an injected, …
  …
One outcome, several metric names: 1 candidate (pick one name; nothing is merged)
  "Order shipped" counted as orders.shipped (orders), shipment.completed (shipping)
```

`logast fleet --vocabulary --central ~/.logast services/*/` scans nothing: it lists per service the keys in its code
that the vocabulary makes aliases, with their places, i.e. what apply would rename once company.yaml (as generated
now) is extended. Without `--central`, each service's own policy counts. For a named fleet, `logast fleet <name>
--vocabulary` writes the worksheet in the fleet folder from the snapshots, and its members extend the fleet's
company.yaml by name.

## Reports written automatically

Every `scan` and every `apply` (not `--dry-run`) writes these from the analysis it has just made, so they describe the
code as it stands after the run; the last line of the run says where they are:

```
reports: .logast/reports/ metrics.md queries.md lifecycle.txt (logast explain metrics|queries|lifecycle prints them)
```

`logast explain metrics` (`queries`, `lifecycle`) prints the same text without writing anything. Besides these,
`scan` writes `orders.json` and [`coverage.txt`](#every-log-call-is-accounted-for), `apply` its
[steps and trace](#apply), and `verify` `verify.json`.

### metrics.md

```
Export: none
  No exporter on the classpath: meters are collected but never leave the application.
Identity
  spring.application.name: shop
  spring.application.version: (not set: manifest Implementation-Version, if any)
Measured without code: …
Entrypoints no metric covers: …
Outcomes logged but not counted: …
```

`logast explain metrics . --format json` has every entry, with the exact metric and tag values to query. More in the
[metrics pack](packs/metrics.md#the-metrics-map).

### queries.md

Queries to paste, one code block each, for what the service measures and logs:

- **Prometheus (PromQL) and New Relic (NRQL):**
  - calls, failure share, average and p95 duration of the HTTP endpoints, scheduled jobs and Kafka listeners that
    Spring times, with each entrypoint's labels;
  - the same for every observation (`ObservedContext`, `@Observed`, `Observation`) and timer in code;
  - every business outcome counter and summary (`Outcomes.count` / `record`, catalogs, Micrometer builders), by
    `outcome`.
- **Splunk (SPL):** errors over time, the most frequent errors, exceptions by type, everything one trace logged,
  everything logged for one business id (each vocabulary MDC key the service uses), the ids with the most errors, and
  the loggers that write the most. Field names follow `logging.structured.format.console` (ecs, logstash, gelf).

Which backends, the Splunk index and the Prometheus service label come from `queries:` in `logast.yaml`
([configuration](configuration.md#queries-ready-made-queries)). Without a Splunk index the Splunk part is left out;
`logast explain queries` says so.

### lifecycle.txt

Every flow by kind (http, message, job, …): whether it has a start and an outcome line, who owns each, and what
`apply` will add; then implementation families (implementations of one interface) that log unevenly.

## Advanced

Not in `--help`, for the cases they name; `apply`, `init` and `fleet` do the usual work.

| command | what it does |
|---|---|
| `logast classpath <svc>` | runs Gradle once and writes `.logast/classpath-*.txt` (compiles nothing). `apply` and `init` do it when the file is missing |
| `logast traffic <svc>` | writes `.logast/decisions/traffic.yaml` with every entrypoint, ready to fill in calls/day (or pass a Prometheus query result with `--traffic`) so logast can judge log volume. Existing values are kept |
| `logast vocabulary <svc>` | draft vocabulary harvested from the code, only terms the policy doesn't cover yet (`--all`: everything); `init` and the first `apply` write the same harvest into `logast.yaml`. How to organise it: [vocabulary.md](vocabulary.md#start-from-the-code) |
| `logast vocabulary --central <dir> <svc>…` | one vocabulary across services: every key with its evidence in `<dir>/vocabulary-worksheet.yaml` (decide there: `term`, `same_as`, `local`), your decisions kept, decided terms published to `<dir>/company.yaml` ([vocabulary.md](vocabulary.md#one-vocabulary-across-services)). In a named fleet, `logast fleet <name> --vocabulary` does the same without paths ([fleet.md](fleet.md#the-fleets-vocabulary)) |
| `logast vocabulary --prune <svc>…` | removes from logast.yaml the vocabulary entries an extended company.yaml now defines in full; `scan` names them (a named fleet: [fleet.md](fleet.md)) |
| `logast shape <svc>` | exports the code with its own names and words replaced, to send a reproduction ([below](#shape)) |

### Old names

These still work for a release and print one line on stderr with their new form (stdout is unchanged):

| old | now |
|---|---|
| `logast why <svc> <key>` | `logast explain mdc <svc> <key>` |
| `logast lifecycle <svc>` | `logast explain lifecycle <svc>`, or `.logast/reports/lifecycle.txt` |
| `logast metrics <svc>` | `logast explain metrics <svc>`, or `.logast/reports/metrics.md` |
| `logast queries <svc>` | `.logast/reports/queries.md` (written by every scan and apply), or `logast explain queries <svc>` |
| `logast policy <svc>` | `logast explain policy <svc>` |
| `logast rules` | `logast explain rules` |

### shape

`logast shape .` exports the code with its own names and words replaced (`.logast/shape/`, mapping back in
`.logast/shape-mapping.txt`): the same findings without the content, to send a reproduction. Only known public
libraries (Spring, SLF4J, Jakarta, …) keep their names; any other library (a company one under its own group) is
replaced like the code, unless named with `--public-group`. `kept-words.txt` lists every non-generic word that
stayed: read it before sending.

## Let an LLM do the writing

With the Claude Code skill installed (`./install.sh --skill`), ask Claude Code *"bring this service's logging to the
standard"*. It follows [llm-workflow.md](llm-workflow.md):

```bash
logast scan .                       # also writes .logast/decisions/messages.yaml: the LLM fills in the empty "MSG" values
logast apply . --dry-run            # diff + REJECTED reasons (uses .logast/decisions/messages.yaml)
logast apply .
logast scan .                       # until only REPORT-ONLY remains
```
