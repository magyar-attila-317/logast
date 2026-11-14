# Plan: phased rule pipeline

Status: agreed; implementation in the steps below.

## Problem

All rules run in one pass over the original code. A rule knows only what earlier rules happened to publish in a
shared "plan" (planned MDC keys, wrapped methods, silent entrypoints). Lines that rules add are not published at all.
Several rules own the same concern (MDC: 5 groups; lifecycle lines: 6 groups plus the runtime wrapper), so every fix
became one rule peeking at another, and `apply` converged by "retry after rescan".

## Idea

Add first, polish second. Each phase works on the real code the previous phase produced (scratch copy, re-parsed),
so no rule has to guess what another rule intends. Your files are written once, at the end (or only diffed).

```
phase 0  setup        config, dependencies, tracing setup                     (independent)
phase 1  add          1a structure: legacy MDC fixes, observation wrappers     ─┐ rescan between
                      1b lines: lifecycle, outcomes, skips, failures          ─┘
phase 2  polish       2a MDC placement
                      2b messages (drop what MDC carries, format, wording)
                      2c exceptions (log-and-rethrow, logged twice)
                      2d levels and volume
                      2e reports and decisions (metrics, resilience, errors, audit)
```
Within a step, rules must not share a concern. Between steps there is a rescan, so a later step sees the real result
of the earlier one. The shared plan state (`plannedOwnKeys`, `plannedLocalKeys`, `plannedBy`, `freezePlan`,
`wrappedMethods`, `silentPlanned`) goes away step by step.

## Rules per phase

| step | rules | what the step may assume |
|---|---|---|
| 0 setup | SETUP_SUPPORT_STARTER, SETUP_OTEL_STARTER, SETUP_LOGBACK_XML, SETUP_STRUCTURED_LOGGING, SETUP_SERVICE_IDENTITY, TRACING_SAMPLING, KAFKA_OBSERVATION_DISABLED, HTTP_CLIENT_UNTRACED, HTTP_CLIENT_NO_TIMEOUT, JOB_LAUNCH_UNTRACED | nothing |
| 1a structure | MDC_NOT_CLOSEABLE, MDC_CLEAR, MDC_NOT_OWNER (legacy MDC to scopes), ENTRYPOINT_NOT_OBSERVED (wrapper only, no keys), SCHEDULED_TASK_UNTRACED, ASYNC_CONTEXT_LOSS, BUSINESS_EVENT_UNMEASURED | support classes exist |
| 1b lines | ENTRYPOINT_SILENT, JOB_OUTCOME_MISSING, BATCH_SUMMARY_MISSING, SKIP_UNLOGGED, STATE_CHANGE_UNLOGGED, DB_OUTCOME_UNLOGGED, LIFECYCLE_UNEVEN, SWALLOWED_EXCEPTION, LOST_THROWABLE | wrappers are in the code (their runtime outcome line counts) |
| 2a MDC | MISSING_DOMAIN_MDC (entrypoint keys, added to the wrapper or as a scope), REPEATED_LOG_VALUE (highest stable point of a chain), MDC_KEY_ALIAS, MDC_NESTED_KEY, AREA_MDC_MISSING (report) | every line exists |
| 2b messages | LOG_FORMAT (incl. dropping keys MDC carries), STRING_CONCAT_LOG, LOG_KEY_STYLE, GLOSSARY_VIOLATION, MESSAGE_PHRASING, PII_IN_LOG, LOG_OBJECT_ARGUMENT, LOG_COLLECTION_ARGUMENT, LOG_KEY_VALUE_IN_MDC, MESSAGE_SUGGESTED, MESSAGE_VAGUE (report), LOG_HELPER (report) | MDC scopes are in the code |
| 2c exceptions | LOG_AND_RETHROW, EXCEPTION_LOGGED_TWICE | context is in MDC where it will be |
| 2d levels | LOG_LEVEL (client errors, loops over data, DB read frequency), LOG_IN_LOOP (report), LOG_VOLUME (report) | final lines and wording |
| 2e reports | METRIC_* incl. METRIC_OUTCOME_UNMEASURED (metrics.json decisions), UNBOUNDED_EXECUTOR, KAFKA_NO_ERROR_HANDLER, BLOCKING_WAIT_UNBOUNDED, ROW_BY_ROW_WRITE, ERROR_DETAILS_EXPOSED, UNMAPPED_EXCEPTION, CLIENT_ERROR_AS_SERVER_ERROR, PROBLEM_DETAILS_DISABLED, AUDIT_*, MANUAL_TRACE_MDC | final code |

## Ownership (written precedence)

Lifecycle, decided once in 1b:
- A run (`@Scheduled`, batch runner): started at the top, completed at the end with the counts of the loops it ran;
  in a transaction the completed line goes through `AfterCommit`.
- A message or event entrypoint: one outcome line. An observation wrapper's runtime line (completed/failed with
  duration) is that outcome; nothing else adds one.
- An HTTP endpoint that changes data and logs nothing of its own: a started line (access logs cover the rest).
- The callee owns facts only it has: DB outcome (rows, counts), state changes, skips, in the method doing them.
- Implementations of one abstract/interface method log the same lifecycle, each in its own body.
- A loop over data: one summary line after it; per-item lines are at most DEBUG (2d).

Context, decided once in 2a:
- "Stable" means: the same value for the whole flow below the scope. A loop variable is never stable.
- MDC goes into the highest method that has the value, never nested under a scope for the same key.

Lines added in 1b carry the values in scope as `[k=v]` (vocabulary ids among the method's parameters and locals);
2a/2b move them to MDC and drop them where MDC carries them.

## Mechanics

- Every rule declares its step; `Rules.scan(analysis, step)` runs one step.
- `Applier` runs the steps in order on the scratch copy: per step, apply rounds until no automatic order is left,
  then rescan (a full parse from source each time: results never depend on compiled classes) and continue. Filled texts (fills.json) and metric decisions
  (metrics.json) apply in their step.
- Order ids are anchored on content (method, kind, message), never on line numbers, so they survive earlier steps.
- `logast scan`: unchanged single-pass diagnostic (decision 2).

## Tests

- e2e snapshot stays the main safety net; its output records what each step applied.
- Invariants checked on the e2e result and the fixture: no key both in MDC and in a message below its scope; no
  nested scope for one key; at most one started and one completed per flow method; a second apply changes nothing;
  the result compiles.
- The fixture markers describe single-scan findings on the original code. They stay for `scan` (diagnostics); the
  phased result is covered by e2e and the invariants.

## Steps

1. Give every rule its step, document the table, `Rules.scan(analysis, step)`; no behaviour change intended.
2. Phased `Applier`; review the e2e diff.
3. Split responsibilities that now cross steps: the wrapper (1a) from its MDC keys (2a); lines in 1b with `[k=v]`;
   levels for added lines decided in 2d only.
4. Remove the shared plan state one piece at a time, each with e2e and fixture green.
5. Invariant checks.
6. Redo the parked work inside the new steps: job start/summary lines, summary via `AfterCommit`, ROW_BY_ROW_WRITE,
   duplicate ids.

## Decisions

1. Added lines get generated wording, marked `// logast:wording`; fills.json asks for better words.
2. `logast scan` stays a quick single-pass diagnostic; no effort goes into it. `logast apply` runs the full pipeline,
   and the person reviews the diff and drops or changes what they don't like.
3. Values in added lines: every id-like variable in scope (vocabulary ids first), at most 5 per line.
