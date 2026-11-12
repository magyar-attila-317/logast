---
name: logast
description: Bring a Spring Boot service's logging, MDC and tracing to the house standard with the logast CLI. Use when asked to fix, standardise or review logging/MDC/tracing in a Spring Boot (Gradle) project.
---

# Standardise logging with logast

logast decides **what** to change and **where**. You only write message text. Do not edit
logging code by hand and do not read source files unless an order is REPORT-ONLY.

## Loop

1. `logast init <root>` once per project if it has no `logast.yaml` yet (it also writes the classpath);
   `logast classpath <root>` again after dependency changes. It runs Gradle
   and writes `.logast/classpath-*.txt`. If `scan` warns about a missing classpath or unresolved
   calls, run it first.
2. `logast scan <root>` prints every work order. The full JSON goes to `<root>/.logast/orders.json`.
   - **AUTO**: no input needed.
   - **NEEDS TEXT**: write the message for each slot (usually `MSG`).
   - **REPORT-ONLY**: needs a human decision. Read its `note` and mention it in your summary;
     don't fix it by hand unless asked.
3. Fill in `.logast/fills.json` (the scan created it: one entry per NEEDS TEXT order, with `_MSG` and `_where`
   hints). Only replace the empty `"MSG"` values; leave the `_…` keys as they are:
   ```json
   {
     "SWA-1a2b3c": {"MSG": "Invoice export failed, nightly job will retry", "_MSG": "…", "_where": "…"},
     "FMT-4d5e6f": {"MSG": "Exporting invoice", "args": ["batchSize"], "_MSG": "…", "_where": "…"}
   }
   ```
   Entries you leave empty are skipped. Re-scanning keeps what you wrote.
   Text rules (they are checked):
   - Plain sentence text, single line, at most 160 characters. Describe what happened or what
     is starting in domain terms.
   - No `{}` placeholders and no `[k=v]`. The tool appends the ` [key={}, ...]` suffix from the
     order's `suffix keys`, and passes the exception itself.
   - Use the glossary terms printed at the top. Banned synonyms are rejected.
   - If a slot says `must read as '{subject} failed'` (or `started`, …), follow that form:
     "Payment charge failed, reconciliation will retry", "Order reconciliation started".
   - Don't repeat values that are in `mdc:`; they are already on every log line.
   - `args` (optional) adds variables from `vars:` to the suffix. Use it only for values that
     help diagnose the problem. Never add PII.
4. Dry run: `logast apply <root> --auto` (it uses `.logast/fills.json` automatically). Check the diff and the
   `REJECTED` lines, fix the texts in the file, and repeat.
5. `logast apply <root> --auto --write`
6. If `apply` lists `logast classpath .` under next steps (it does after adding the logging-support
   dependency), run it. Otherwise the new `ObservedContext`/`MdcScope` calls show up as unresolved.
7. `logast scan <root>` again. Only REPORT-ONLY orders should remain. New NEEDS TEXT orders can
   appear after structural changes: repeat from step 3.

`apply` works on a scratch copy. It applies structural fixes first, rescans and applies the rest.
Order ids are stable across rescans, so a fill stays valid. A fix can create a *new* follow-up order (e.g. logging
a swallowed exception turns an upstream log-and-rethrow into EXCEPTION_LOGGED_TWICE); the order's `note` says so.
Handle follow-ups in the next round.

## Helpful shortcuts

- `--fills <file>` uses another file instead of `.logast/fills.json`; `logast scan <root> --format fills-template`
  prints the skeleton for one.
- After `apply --write`, if the project has tests, `logast verify <root>` runs them with log capture. It reports
  where context (trace or MDC keys) got lost across threads at runtime, and values logged as `null`. Mention its
  findings; they are report-only.

## Useful options

- `scan --rule SWALLOWED_EXCEPTION`, `--entrypoint "POST /orders"`, `--severity high` and
  `--limit 20` keep the output small.
- `explain <root>` lists the entrypoints. `explain <root> --entrypoint "POST /orders"` shows the
  call tree with log/MDC/async markers and order ids.
- `scan --fail-on high` exits with 2 when there are findings, for CI. Add `--baseline` (after a one-off
  `--update-baseline`) so only new findings count.
- `--pack logging|tracing|metrics` limits a run to one concern.
- `// logast:ignore RULE` on a line (or the line above) suppresses a finding. Use it only when
  the user agrees.

## What the standard is (so your text fits)

- Message: `Text [key1=value1, key2=value2]`. Context lives in MDC, not in the text.
- MDC is always scoped with try-with-resources (`MdcScope`). Trace and span ids come from
  OpenTelemetry, never set by hand.
- Entrypoints the framework doesn't observe get an `ObservedContext` (span plus MDC). Its `close()`
  only restores MDC and the scope. `error()` in catch and `stop()` in finally are intentional:
  this is Micrometer's documented order.
  Batch jobs continue the trace from `TRACEPARENT`/`TRACE_ID`.
- Logs are ECS JSON with `service.name`/`service.version` taken from `SERVICE_NAME`/`APP_VERSION`.
