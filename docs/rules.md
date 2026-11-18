# Rules and packs

Rules are grouped into packs. A rule runs when any of its packs is selected (`packs:` in `logast.yaml`, or
`--pack` on the command line). `logast rules` prints every rule id with its packs; that list comes from the code and
is the authority if this page and the tool ever disagree.

| pack | default | covers | rules |
|---|---|---|---|
| [logging](packs/logging.md) | on | message format and wording, exceptions, MDC context, what goes into a line, log coverage, levels | [table](packs/logging.md#rules) |
| [tracing](packs/tracing.md) | on | trace context across threads, HTTP, messaging and job launches; spans; export; trace ids in logs | [table](packs/tracing.md#rules) |
| [metrics](packs/metrics.md) | opt-in | meter names and tags, business events and outcomes without a metric | [table](packs/metrics.md#rules) |
| [resilience](packs/resilience.md) | opt-in | timeouts, pools, retries, locks, graceful shutdown, health probes | [table](packs/resilience.md#rules) |
| [errors](packs/errors.md) | opt-in | error responses: leaked details, unmapped exceptions, status codes, ProblemDetail, lost causes | [table](packs/errors.md#rules) |
| [audit](packs/audit.md) | opt-in | audit records: missing, incomplete, uncommitted, unrouted, holding secrets | [table](packs/audit.md#rules) |

Rules in more than one pack (a pack selected on its own brings them along):

| rule | packs |
|---|---|
| SETUP_SUPPORT_STARTER | logging, tracing, metrics, errors (every pack whose fixes use the [runtime starter](starter.md)) |
| SETUP_SERVICE_IDENTITY | logging, tracing, metrics |
| ASYNC_CONTEXT_LOSS, SCHEDULED_TASK_UNTRACED | logging, tracing |
| OBSERVED_ANNOTATION_INACTIVE | tracing, metrics |

The RUNTIME_* rules come from `logast verify` (what the tests really logged), not from `scan`: RUNTIME_MDC_LOST and
RUNTIME_NULL_VALUE belong to logging, RUNTIME_TRACE_LOST to tracing.

## How to read the rule tables

- **severity**: the rule's default (`low`, `medium`, `high`). `--severity` and `--fail-on` filter by it; a policy can
  change it (`rules: { LOG_FORMAT: { severity: low } }`).
- **fix**: what a finding of the rule turns into.
  - **AUTO**: logast makes the change.
  - **NEEDS TEXT**: logast decides the change; a person or an LLM writes one message in `.logast/fills.json`
    (or in `logast review`).
  - **report** (REPORT-ONLY): a decision for a person. The finding says what to decide and when it counts as done.
  - "AUTO …; report otherwise": the rule fixes the shapes it can and reports the rest.

## Switching rules off

```yaml
rules:
  ENTRYPOINT_SILENT: { severity: low }
  LOG_IN_LOOP: { enabled: false }
```

In code:

- `// logast:ignore RULE` on the line or the line above silences one finding. Several rules: `RULE_A, RULE_B` (commas
  or spaces); a reason may follow the list. Rule ids match exactly. A bare `// logast:ignore`, with nothing after it,
  silences every rule there.
- `// logast:ignore-file RULE` silences a rule for a whole Java file.
