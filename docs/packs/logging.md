# logging pack

Log lines in one format and one vocabulary, exceptions logged once where they are handled, business ids in MDC from
the entrypoint down, and a log line wherever a flow would otherwise be silent.

- **On by default** (`packs: [logging, tracing]`).
- **Settings:** `vocabulary`, `areas`, `logging`, `phrasing`, `levels`, `mdc`, `pii` in
  [configuration.md](../configuration.md); how the vocabulary and phrasing work: [vocabulary.md](../vocabulary.md).
- ASYNC_CONTEXT_LOSS, SCHEDULED_TASK_UNTRACED (context lost on another thread), SSE_BROADCAST_CONTEXT and
  REACTIVE_CONTEXT_LOSS (trace ids lost in Reactor operators) are in both this pack and [tracing](tracing.md#rules),
  where they are listed.

Legend: [rules.md](../rules.md#how-to-read-the-rule-tables). The fix column names the usual outcome; where a rule
can end either way, the finding's note says why.

## Rules

| rule | severity | finds | fix |
|---|---|---|---|
| **Exceptions** | | | |
| SWALLOWED_EXCEPTION | high | a caught exception dropped without a log line | NEEDS TEXT: logast adds the line (level `logging.swallowedLevel`), you write the message |
| LOST_THROWABLE | medium | a log line in a catch block that does not pass the exception to the logger | AUTO |
| LOG_AND_RETHROW | medium | an exception logged and rethrown | AUTO: the line goes once every caller path logs it; report otherwise |
| EXCEPTION_LOGGED_TWICE | medium | the same exception logged here and again where it is handled | AUTO: log once, where it is handled |
| **Message format and wording** | | | |
| LOG_FORMAT | low | a message not in the house `Text [k=v]` form | AUTO; NEEDS TEXT where the wording has to be written |
| STRING_CONCAT_LOG | low | a message built by string concatenation | AUTO: placeholders and `[k=v]` keys |
| GLOSSARY_VIOLATION | low | a banned word (a vocabulary alias) instead of the business term | AUTO: the term |
| MESSAGE_PHRASING | low | lifecycle wording that differs from the house form (`phrasing`) | AUTO: `X started` / `X completed` / `X failed` … |
| MESSAGE_VAGUE | low | wording on the avoid-list (`logging.vagueMessages` plus the built-in list) | AUTO where it can be rephrased; report otherwise |
| BUSINESS_EVENT_TIMESTAMP | low | the current time logged as a value (every log line has a timestamp) | AUTO: the value goes |
| LOG_KEY_STYLE | low | `[k=v]` keys, and fluent `addKeyValue(..)` property keys, that differ from the vocabulary or `logging.keyStyle` | AUTO |
| LOG_KEY_NESTING_CONFLICT | medium | a log field `x` next to a field `x.y` (vocabulary, MDC, `[k=v]`, `addKeyValue`): the search index maps `x` as an object (Elasticsearch expands dotted names; Boot's ECS format nests them), so lines with a plain `x` are rejected | report: give one of them another name |
| LOG_ID_MISSING | low | a WARN / ERROR or outcome line that leaves out a business id its method has at hand (a vocabulary parameter, or the id a parameter carries: getter / record accessor) and MDC does not carry there | AUTO with one candidate (`[orderId={}]` and its value, before a trailing exception); a decision with several |
| PII_IN_LOG | high | a value whose name is on the `pii` list | AUTO |
| MESSAGE_SUGGESTED | low | a line logast generated with placeholder wording (`// logast:wording`) | NEEDS TEXT: `messages.yaml` asks for better wording |
| **What goes into a line** | | | |
| LOG_OBJECT_ARGUMENT | high | a whole DTO or entity logged (its `toString`, lazy loading) | AUTO: its id |
| LOG_COLLECTION_ARGUMENT | medium | a whole collection logged | AUTO: its size |
| LOG_HELPER | medium | logging through a helper method | report: log at the call site |
| LOG_KEY_VALUE_IN_MDC | low | a `[k=v]` that repeats a key MDC already carries | AUTO: removed from the message |
| **MDC** | | | |
| MISSING_DOMAIN_MDC | medium | an entrypoint whose business id is not in MDC | AUTO: `MdcScope` around the body; report for a method returning a `Mono`/`Flux` (a scope would end before the pipeline runs: the note names the Reactor Context route) |
| REPEATED_LOG_VALUE | medium | the same id logged by every message of a method and the methods it passes it to | AUTO: into MDC once |
| MDC_NOT_CLOSEABLE | low | `MDC.put` never removed, or cleared in a `finally` | AUTO: one `MdcScope` (restores exactly what it changed) |
| MDC_CLEAR | high | `MDC.clear()`: wipes the caller's context, trace ids included | report; folded into the MDC_NOT_CLOSEABLE fix |
| MDC_NESTED_KEY | medium | a scope for a key already set on every path into the method | AUTO: unwrapped when the value is the caller's; report otherwise |
| MDC_NOT_OWNER | high | MDC written while a bean is created (startup thread) | AUTO: moved to the flow that uses the value |
| MDC_KEY_ALIAS | medium | an MDC key spelled as an alias of a vocabulary key | AUTO: renamed |
| AREA_MDC_MISSING | medium | a key an `areas` entry requires, missing in logging methods of that area | report |
| **Log coverage** (see [below](#log-coverage)) | | | |
| ENTRYPOINT_SILENT | medium | an entrypoint whose own flow logs nothing at INFO on the normal path | NEEDS TEXT |
| JOB_OUTCOME_MISSING | medium | a job or listener without an outcome line | AUTO |
| SKIP_UNLOGGED | low | an early `return` in a job that says nothing | NEEDS TEXT |
| STATE_CHANGE_UNLOGGED | medium | a repository save/delete in a method that logs nothing | NEEDS TEXT |
| BATCH_SUMMARY_MISSING | low | a loop on a job path doing I/O per item, without a total | NEEDS TEXT |
| DB_OUTCOME_UNLOGGED | medium | a JDBC statement whose outcome nobody logs | AUTO (wording marked for review) |
| LIFECYCLE_UNEVEN | low | implementations of one method that log their lifecycle differently | AUTO: the missing lines |
| **Levels and volume** (see [below](#noise-logs-in-loops-and-real-volume)) | | | |
| LOG_LEVEL | medium | a level that does not fit: INFO per loop item, ERROR for a client error (`levels.clientErrors`) | AUTO |
| LOG_IN_LOOP | low | INFO per item of a loop of unknown size | report: a `// logast:loop-size` comment settles it |
| LOG_VOLUME | medium | a statement above `levels.volumeBudget` lines/day (needs `traffic.yaml` rates) | report |
| **Setup** | | | |
| SETUP_SUPPORT_STARTER | high | the [runtime starter](../starter.md) the fixes use is missing (also tracing, metrics, errors) | AUTO |
| SETUP_LOGBACK_XML | medium | a hand-rolled `logback-spring.xml` | AUTO: Spring Boot structured logging instead |
| SETUP_STRUCTURED_LOGGING | medium | no structured log format, or one other than `logging.structuredFormat` | AUTO |
| SETUP_SERVICE_IDENTITY | medium | logs and meters that do not say which service and version wrote them (also tracing, metrics) | AUTO |
| **Runtime** (`logast verify`) | | | |
| RUNTIME_MDC_LOST | high | a test's log line on another thread without MDC the flow had before | report |
| RUNTIME_NULL_VALUE | medium | a logged value rendered as `null` | report |

## What the fixes look like

An identifier repeated in every message of a method goes into MDC once (REPEATED_LOG_VALUE), and the messages lose
the now redundant `[k=v]`:
```java
public void refund(String orderId, long amountCents) {
    try (var mdc = MdcScope.of("order.id", orderId)) {
        log.info("Refund started");
        check(amountCents);
        log.info("Refund completed [amount.cents={}]", amountCents);
    }
}
```

Swallowed exception (the message text is what a person or LLM wrote; the rest is logast's):
```java
} catch (PaymentException e) {
    log.warn("Payment charge failed, reconciliation job will retry [order.id={}]", order.id(), e);
```

### MDC

`MDC.put` / `finally { MDC.clear(); }` (which also wipes the caller's context) becomes:
```java
try (var mdc = MdcScope.of("audit.action", action, "tenant.id", tenantId)) {   // alias tenantId → tenant.id
    log.info("Audit entry written");
}
```

Whether a logged value is the value an MDC key holds is followed back through the code, not guessed from its name:
the variable or accessor of the key's name, a local initialised from one and never reassigned (`String code =
runCode;`), or a parameter every caller passes one into (up to three calls up). Anything else keeps its key in the
message.

### Messages

```diff
- log.info("Placed purchase " + order.id());
+ log.info("Placed order [order.id={}]", order.id());
- log.warn("Refund failed for order {}", orderId);            // order.id is already in MDC; exception was lost
+ log.warn("Refund failed", e);
- log.info("Starting reconciliation for tenant {}", tenant);
+ log.info("Reconciliation for tenant {} started", tenant);
```

### What goes into a log line

```java
log.info("Coupon batch received [couponIds={}]", couponIds);   // LOG_COLLECTION_ARGUMENT → [couponCount={}], couponIds.size()
log.info("Order attached [order={}]", order);                  // LOG_OBJECT_ARGUMENT → order.id(): no toString of a whole
                                                                //   DTO/entity (lazy loading = a query inside a log call)
log.info("Loaded [customer={}]", ticket.getCustomer().getName()); // reported: navigates a lazy association, may NPE
log.info("No Coupon entries found, won't publish");           // MESSAGE_VAGUE → "No coupon entries found, will not publish"
log.debug("Processing...");                                    // MESSAGE_VAGUE (reported): say what started/completed/failed
log.info("Refund started [now={}]", LocalDateTime.now());      // BUSINESS_EVENT_TIMESTAMP → "Refund started"
LogHelper.logCollection(log, "Prepared", items);               // LOG_HELPER (reported): log at the call site instead
```
Collections with a fixed size (`List.of(..)`, constants, enum sets, `limit(..)`) are left alone. A value record of counts,
or a class with its own `toString()`, is not an object dump.

### MDC is owned once, where the flow starts

```java
try {                                     // legacy block one call below an entrypoint that scopes couponId already:
    MDC.put(COUPON_ID, couponId);         //   MDC_NOT_CLOSEABLE + MDC_NESTED_KEY + MDC_CLEAR → the whole block goes,
    log.info("Calculation started");      //   the log lines stay, the entrypoint's scope carries the context
} finally {
    MDC.clear();                          // MDC_CLEAR: never wipe the caller's context (trace ids included)
}
```
`static final` key constants are understood; the generated scope keeps the constant. No new scope or observation is
added around code that still writes MDC the old way: the MDC fix comes first, the wraps follow after the rescan.

### Protected messages

Messages that alerts or dashboards depend on are never reworded or reformatted:
```yaml
logging:
  protectedMessages: ["^ALERT:", "Payment reconciliation mismatch"]   # regexes; reported, not rewritten
  vagueMessages: ["^handled\\.?$"]                                  # added to the built-in avoid-list
```

## Log coverage

Where logs are missing, logast adds the line and someone writes the sentence.

The other direction, the log lines that exist: `.logast/reports/coverage.txt` says for every one of them whether logast
fixed, reported, ignored, skipped (and why) or checked it; see
[every log call is accounted for](../commands.md#every-log-call-is-accounted-for).

An entrypoint (an endpoint that changes something, a listener, a job) is "silent" when its own flow logs nothing at
INFO on the normal path: the method itself, the service it calls and one level below. Lines deeper in shared code
(event publishers, audit, helpers) and lines in catch blocks don't count; they don't say which flow ran. GET
endpoints are left alone. Plain helper classes get no log lines of their own: logs belong to flows.
```java
@Scheduled(cron = "0 0 2 * * *")
public void export() {
    log.info("Invoice export started");                              // ENTRYPOINT_SILENT
    List<Invoice> open = invoices.findBySentFalse();
    if (open.isEmpty()) {
        log.info("Invoice export skipped, no open invoices");        // SKIP_UNLOGGED: why nothing happened
        return;
    }
    sender.sendAll(open);
    log.info("Invoice export completed");                            // JOB_OUTCOME_MISSING
}

public void sendAll(List<Invoice> open) {
    for (Invoice invoice : open) {
        send(invoice);
    }
    log.info("Invoice batch completed [invoiceCount={}]", open.size()); // BATCH_SUMMARY_MISSING
}

public Invoice cancel(String invoiceId) {
    var result = invoices.save(new Invoice(invoiceId, null, false)); // a returned value is extracted first,
    log.info("Invoice cancelled [invoiceId={}]", result.id());      // STATE_CHANGE_UNLOGGED, so it can be logged
    return result;
}
```
- **Jobs and listeners** (scheduled, batch runners, Kafka/JMS/Rabbit, event and WebSocket handlers) get an outcome
  line; scheduled and batch runs also a start line. HTTP requests are left out: access logs and `http.server.requests`
  already show them.
- **Skipped work**: an early `return` in a job or the methods it calls directly (INFO in scheduled/batch runs,
  DEBUG elsewhere). Only `void` methods: an early return that hands back a value is logic, not a skip.
- **State changes**: a repository save/delete in a method that logs nothing; the entity's id is logged.
- **Database outcomes** (`JdbcTemplate`, `NamedParameterJdbcTemplate`, `JdbcClient`): a method running one statement
  and logging nothing gets its outcome logged in the method itself, the one place that has the facts:
  ```java
  int rows = jdbcTemplate.update(CLOSE_ITEMS, orderId);
  AfterCommit.log(() -> log.info("Order items updated [rows={}]", rows)); // logast:wording
  ```
  Write or read is told from the SQL (an upsert through `queryForObject` is a write). Writes: INFO, written only
  once the transaction commits (`AfterCommit`), so a rolled-back change is never announced. Reads: INFO when only
  low-frequency paths reach them (jobs, startup, or a `traffic.yaml` rate within `levels.volumeBudget`), DEBUG
  otherwise. Called inside a loop: DEBUG.
  The wording comes from the table name; `// logast:wording` marks it, and `messages.yaml` asks for a better one
  (MESSAGE_SUGGESTED). Sequences and DDL are left alone.
- **Batch summaries**: a loop on a job path that does I/O per item (repository, HTTP, messaging). Loops over a call
  (`for (Order o : repository.findAll())`) get the call extracted into a variable first, so the count doesn't run
  the query again.

The scan's header shows the service's **log coverage**, the share of these places that already log:
```
log coverage 53.7% (jobs 68/214 · http 409/631 · skips 0/50 · stateChanges 174/310 · loops 14/33)
```
It is also a column in the fleet report. Turn single rules off with `rules: { SKIP_UNLOGGED: { enabled: false } }`.

## Noise: logs in loops, and real volume

INFO once per loop item is fine for 10 items and a flood for 50,000, so logast judges by where the items come from
(`levels.infoInLoops: size`, the default):

| loop over | INFO per item |
|---|---|
| a fixed list, enum values, a constant, up to `boundedLoopMax` (20) items | fine |
| database rows (repository `find…`/`stream…`, JDBC queries, pages), file lines, a Kafka batch | lowered to DEBUG (LOG_LEVEL); the batch-summary rule adds one total after the loop |
| anything else (size unknown) | reported (LOG_IN_LOOP); a comment above the loop settles it |

```java
// logast:loop-size 10       (or small): INFO stays
// logast:loop-size large    DEBUG, plus a summary after the loop
for (Store store : stores) { ... }
```

`never` lowers every loop that isn't known to be small, `always` turns the checks off. With rates
in `.logast/decisions/traffic.yaml` (`logast traffic .` lists every entrypoint for you; a Prometheus query result works
too), `LOG_VOLUME` reports lines/day per statement over your budget. logast never guesses traffic.
