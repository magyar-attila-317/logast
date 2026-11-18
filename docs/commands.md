# Commands

Every command prints its options with `--help` (`logast scan --help`). This page explains what they are for, with
examples. Output samples are illustrations; counts and ids differ per service and version.

| command | what it does |
|---|---|
| `logast init [svc]` | one-time setup: `logast.yaml`, `.gitignore`, classpath, traffic template (`--baseline` for CI) |
| `logast classpath <svc>` | runs Gradle once and writes `.logast/classpath-*.txt` (compiles nothing) |
| `logast scan <svc>` | finds gaps, writes `.logast/orders.json`, prints the work orders |
| `logast apply <svc> [--auto] [--only ids] [--write]` | applies orders on a scratch copy, rescanning between rounds; dry run unless `--write`. Message texts come from `.logast/fills.json` (or `--fills <file>`) |
| `logast review <svc> [--open]` | local page for people to write the messages and apply |
| `logast verify <svc>` | runs the existing tests with log capture and checks what was really logged |
| `logast explain <svc> [--entrypoint "POST /x"]` | lists entrypoints, or the call tree below one with log/MDC/async markers |
| `logast lifecycle <svc>` | every flow's lifecycle lines: which exist, who owns the start and the outcome, what logast will add; implementation families that log unevenly |
| `logast why <svc> <key>` | why an MDC key is scoped where it is: every method that logs it, the decision (scope, covered, in MDC, no scope, lifted) and the reason |
| `logast metrics <svc>` | the metrics map: exporters, identity, metric per entrypoint, meters in code, outcomes not counted (`--format json`, `--format table` = meters only) |
| `logast queries <svc>` | ready-made PromQL, NRQL and Splunk SPL for the service's metrics and logs, written to `.logast/queries.md` (`--stdout` prints them) |
| `logast vocabulary <svc>` | draft vocabulary harvested from the code, only terms the policy doesn't cover yet (`--all`: everything) |
| `logast traffic <svc>` | writes `.logast/traffic.yaml` with every entrypoint, ready to fill in |
| `logast policy <svc>` | the effective policy after all layers, with overrides |
| `logast rules` | every rule and its packs |
| `logast fleet <svc>…` | compliance report across services |
| `logast shape <svc>` | exports the code with its own names and words replaced, to send a reproduction ([below](#shape)) |

Options most analysing commands share: `--policy <file>`, `--classpath-file <file>`, `--pack <packs>`,
`--traffic <file>`.

Output is coloured in a terminal, and long steps (`scan`, `apply`) show a live progress line: the current step, the
round, the number of fixes done and the elapsed time. In CI or a pipe each step is printed as a plain line instead.
`NO_COLOR=1` turns colours off, `LOGAST_COLOR=always|never` forces them.

## init

| option | what it does |
|---|---|
| `--extends <url\|file>` | build on your company's shared settings (comma-separated) |
| `--key-style <style>` | spelling of MDC and `[k=v]` keys: `dotted`, `camel`, `pascal`, `snake`. Without it: the style the code already uses most, else `camel` |
| `--keep-wording` | legacy service: fix structure, keep the wording of existing messages |
| `--support vendor` | copy the [starter](starter.md)'s classes into the service instead of adding the library (until it is in your repository) |
| `--pack logging,tracing,metrics` | which packs run (default `logging,tracing`; also `metrics`, `resilience`, `errors`, `audit`) |
| `--baseline` | scan once and record today's findings for CI |
| `--no-classpath` / `--no-traffic` | skip those steps |
| `--force` | write a fresh `logast.yaml` over an existing one |

`init` is safe to re-run: it keeps the files you already edited. It does not create `fills.json` / `metrics.json`:
their entries belong to the code the first `apply` writes (generated lines, database and job outcome lines) and to
the wording it leaves, so they only make sense after it. Each step is also its own command:
`logast classpath .`, `logast traffic .`, `logast scan . --update-baseline`.

## scan

```
$ logast scan .
logast · 63 files · 21 entrypoints · 49/147 methods reachable · 52 call edges
log coverage 44.0% (jobs 6/10 · http 5/11 · skips 0/1 · loops 0/1 · stateChanges 0/2)   (places that should log and do)
165 orders: 88 AUTO, 10 NEEDS TEXT, 67 REPORT-ONLY   (AUTO = logast fixes it · NEEDS TEXT = … · REPORT-ONLY = …)
glossary terms: tenant, order, payment, charge, refund, shipment, customer, reconciliation, confirmation
…

SWA-d5b58b  low  NEEDS TEXT  SWALLOWED_EXCEPTION  src/main/java/com/example/shop/service/DefaultOrderService.java:41
  PaymentException is caught and dropped without a log line in DefaultOrderService.place (documented as intentional)
  path: POST /orders → OrderController.create → DefaultOrderService.place
  + log.warn("{{MSG}}", order.id(){{ARGS}}, e);
  MSG: What failed and what happens next … | suffix keys: [order.id] | must read as '{subject} failed'
  note: PaymentClient.charge already logs this exception before rethrowing it. After this fix that log becomes
        an EXCEPTION_LOGGED_TWICE order to delete (log once, where it is handled).

DOM-f22aa2  medium  AUTO  MISSING_DOMAIN_MDC  src/main/java/com/example/shop/web/OrderController.java:37
  GET /orders/{orderId}: MDC lacks order.id
  + try (var mdc = MdcScope.of("order.id", orderId)) {
  + … (4 lines)
```

- Narrow the list: `--rule`, `--pack`, `--severity high`, `--entrypoint "POST /orders"`, `--limit`.
- Change the output: `--format compact` (default) `| summary | json | sarif | fills-template`.
- CI: `--baseline`, `--update-baseline`, `--since <git ref>`, `--fail-on <severity>` (see the README's CI section).
- `scan` also creates and updates `.logast/fills.json` and `.logast/metrics.json`: it keeps every entry you already
  have exactly as it is, adds entries for new findings and removes the ones that are done. Nothing is rewritten when
  nothing changed.

### What happens next

The end of `scan` and `apply` is for a person: it groups what is left by what you do about it, not by rule.
`scan --format summary` prints only that part, plus the worst findings that wait on you:

```
Applied earlier: 100 changes in 53 files (git diff to review)

Waiting on you — logast can't continue without these:
  21 log lines need wording        → write "MSG" in .logast/fills.json
  12 outcomes: count, tag or skip  → .logast/metrics.json
  34 decisions                     → logast scan .   (each says what to decide)
     mostly: vague message 6, incomplete audit record 5, async work loses the log context 4, …
  then run: logast apply . --auto --write   (on this code: don't revert it)

Fix first (high):
  async work loses the log context   src/…/EdgeFlow.java:44          ASYNC_CONTEXT_LOSS
  async work loses the log context   src/…/QuoteController.java:18   ASYNC_CONTEXT_LOSS
  async work loses the log context   src/…/QuoteController.java:19   ASYNC_CONTEXT_LOSS
  async work loses the log context   src/…/QuoteController.java:27   ASYNC_CONTEXT_LOSS
  transactions without a timeout     src/…/application.yml:1         DB_NO_TIMEOUT
  (23 medium, 39 low: logast scan .)

Log lines: 85 — 51 fixed, 15 with a finding, 11 fine, 8 not checked (.logast/coverage.txt)
Places that should log: 17 of 21 do (jobs 9/9, endpoints 8/12)
```

- **Applied earlier** appears when an `apply --write` changed files (`.logast/last-apply.txt`).
- **Next** comes first when logast still has fixes of its own to make: `logast apply . --auto --write`. The person's
  work is then listed as what follows, since the apply changes it.
- **Waiting on you** is what logast cannot do: message wording (`fills.json`), whether to count a logged outcome
  (`metrics.json`), and decisions it only reports (the three most frequent kinds by name). `then run` appears when
  something you fill in is applied by the next `apply`; decisions are done by changing the code.
- When nothing is pending and nothing waits on you, it says `Nothing left`.
- **Fix first** lists the findings of the worst severity among those that wait on you (up to 8), by a short name, with
  the rule id last; the rest are counted.
- **Also** holds the optional and follow-up checks (`logast verify .`, traffic rates, vocabulary).

The compact listing (the default) ends with the same blocks, without "Fix first" (the listing is the detail).

The log-coverage line is the share of places that should log (jobs, HTTP endpoints that change something, skips,
state changes, loops) and do; see [log coverage](packs/logging.md#log-coverage). It is also a column in the fleet
report.

### Every log call is accounted for

A line without a finding could mean "checked, fine" or "never looked at", so `scan` (and `apply --write`) also writes
`.logast/coverage.txt`: one line per log call, in file/line order, with what logast did with it:

```
src/main/java/com/example/shop/service/CouponService.java:41   fixed    LOG_FORMAT FMT-e10ae3; reported MESSAGE_VAGUE MVG-53c6bf
src/main/java/com/example/shop/service/CouponService.java:44   checked  no finding
src/main/java/com/example/shop/service/GatewayCalls.java:10    skipped  protected message (logging.protectedMessages)
src/main/java/com/example/shop/service/GatewayCalls.java:14    ignored  LOG_FORMAT
src/main/java/com/example/shop/service/LegacyGateway.java:9    skipped  other logging API: java.util.logging
```

and one line at the end of the run's output (the skip reasons and every line's detail stay in the file):

```
Log lines: 85 — 51 fixed, 15 with a finding, 11 fine, 8 not checked (.logast/coverage.txt)
```

| outcome | meaning |
|---|---|
| `fixed` | an `apply --write` changed it (rule and id). Later runs keep saying so while the file is unchanged since; the file's fingerprint is at the end of `coverage.txt` |
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
file, creates the files to fill (`.logast/fills.json`, `.logast/metrics.json`, `.logast/traffic.yaml`), and ends with
what is left, grouped by what you do about it ([what happens next](#what-happens-next)):

```
applied (100): SLG-d7dbb8 SUP-065aa3 …
files (53): …  ·  10 rounds, 41.2s

Waiting on you — logast can't continue without these:
  21 log lines need wording        → write "MSG" in .logast/fills.json
  12 outcomes: count, tag or skip  → .logast/metrics.json
  34 decisions                     → logast scan .   (each says what to decide)
     mostly: vague message 6, incomplete audit record 5, async work loses the log context 4, …
  then run: logast apply . --auto --write   (on this code: don't revert it)

Log lines: 85 — 51 fixed, 15 with a finding, 11 fine, 8 not checked (.logast/coverage.txt)

Also:
  git diff                                            review the changes (one diff per kind of change in .logast/steps/)
  fill calls/day in .logast/traffic.yaml (optional)   21 of 21 entrypoints have no rate: log levels and metrics frequency stay cautious there
  logast verify .                                     build and run the tests with log capture: does the result really log what it should?
```

One `apply` does everything automatic; a second one is only for what you filled in. Fill the files, run
`logast apply . --auto --write` again on the migrated code, and repeat until nothing is left. `logast scan .` shows
the same without changing anything. `apply --write` records what it changed in `.logast/last-apply.txt`, so a later
scan starts with "Applied earlier: 100 changes in 53 files".

- **Steps.** `apply` runs in steps (setup, structure, lines, mdc, messages, exceptions, levels, reports), each on the
  result of the previous one, and saves one diff per step in `.logast/steps/` (`01-setup.diff`, `02-structure.diff`,
  …). They apply in order with `git apply`, so a whole kind of change can be reviewed on its own, or dropped: later
  steps build on earlier ones, so drop from the end, or rerun with `--until <step>`. Background:
  [pipeline-plan.md](pipeline-plan.md).
- **Checks.** Every run checks its result before writing (no key both in MDC and in a message, no nested scope, one
  started/completed per method, the result still compiles) and prints `CHECK FAILED file:line` if something breaks
  that.
- **Trace.** `.logast/apply-trace.txt` records what each round applied and what its rescan brought up.
- **Coverage.** With `--write`, `.logast/coverage.txt` lists every log call of the result; the ones this run changed
  show as `fixed` ([every log call is accounted for](#every-log-call-is-accounted-for)).
- `--only <ids>` restricts it to some orders; `--format json` prints the result for tools.

## review

```bash
logast review . --open
```

Opens a local page (127.0.0.1, token-protected) with one input per message. Texts already in `.logast/fills.json`
are filled in, and what you type is saved there. It shows the code, the path from the entrypoint, the required
phrasing and the suffix keys. *Dry run* shows the diff and any rejections; *Apply* writes.

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
to `.logast/verify.json`; `--no-run` analyses the captures of the previous run again.

By default it runs `./gradlew test`. Services with other test tasks set them in `logast.yaml` (or pass
`--tasks "test intTest bddTest"`):

```yaml
verify:
  tasks: [test, intTest, bddTest]   # or [check]
  gradleDir: ../..                  # optional: run Gradle from the repo root (service is a subproject)
```

## metrics

```
$ logast metrics .
Export: none
  No exporter on the classpath: meters are collected but never leave the application.
Identity
  spring.application.name: shop
  spring.application.version: (not set: manifest Implementation-Version, if any)
Measured without code: …
Entrypoints no metric covers: …
Outcomes logged but not counted: …
```

`--format json` has every entry, with the exact metric and tag values to query. More in the
[metrics pack](packs/metrics.md#the-metrics-map).

## queries

```
$ logast queries .
queries: 30 PromQL, 20 NRQL, 12 SPL in .logast/queries.md
```

Queries to paste, one code block each, for what the service measures and logs as the code stands (run it after
`apply`):

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
([configuration](configuration.md#queries-ready-made-queries)). Without a Splunk index the Splunk part is left out with
a hint.

## vocabulary, policy, traffic

- `logast vocabulary .` collects the identifier keys the service uses (MDC keys, `[k=v]` keys, id-like variables)
  that the policy doesn't cover yet; `init` writes the same harvest into `logast.yaml`. How to organise it:
  [vocabulary.md](vocabulary.md#start-from-the-code).
- `logast policy .` prints the merged result of all layers and every override.
- `logast traffic .` lists every entrypoint in `.logast/traffic.yaml`; fill in calls/day (or pass a Prometheus query
  result with `--traffic`) so logast can judge log volume. Existing values are kept.

## fleet

```bash
logast fleet services/*/ --out fleet-report       # fleet-report/fleet.html, with trend against the last run
```

A compliance report across services (HTML and a JSON snapshot; earlier snapshots in `--out` give the trend). It also
compares meters across services: the same name with different types or tags, and similar names
(`orders.placed` vs `order_placed`). `@file` reads the service roots from a file.

## shape

`logast shape .` exports the code with its own names and words replaced (`.logast/shape/`, mapping back in
`.logast/shape-mapping.txt`): the same findings without the content, to send a reproduction. Only known public
libraries (Spring, SLF4J, Jakarta, …) keep their names; any other library (a company one under its own group) is
replaced like the code, unless named with `--public-group`. `kept-words.txt` lists every non-generic word that
stayed: read it before sending.

## Let an LLM do the writing

With the Claude Code skill installed (`./install.sh --skill`), ask Claude Code *"bring this service's logging to the
standard"*. It follows [llm-workflow.md](llm-workflow.md):

```bash
logast scan .                       # also writes .logast/fills.json: the LLM fills in the empty "MSG" values
logast apply . --auto               # dry run: diff + REJECTED reasons (uses .logast/fills.json)
logast apply . --auto --write
logast scan .                       # until only REPORT-ONLY remains
```
