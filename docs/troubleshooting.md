# Troubleshooting

## Symptoms

| symptom | cause and fix |
|---|---|
| `WARNING: no classpath` or many unresolved calls | run `logast classpath .`; rerun it after adding dependencies (e.g. after the starter was added) |
| `logast classpath` fails | it only resolves dependencies; run the printed Gradle command without `-q` to see why |
| `REJECTED … must read as '{subject} failed'` | the text has to follow the lifecycle form for that log, e.g. "Payment charge failed, will retry" |
| `REJECTED … glossary 'purchase' → use 'order'` | use the vocabulary term; `logast policy .` shows aliases |
| `depends on [SUP-…]` | apply that order in the same run (`--auto` does) |
| scans are slow on large services | every scan parses the whole project from source, and so does each apply round (`LOGAST_TIMING=1` prints the times): slower, but the result never depends on compiled classes |
| did logast look at this log line? | `.logast/reports/coverage.txt` (written by `scan` and `apply --write` with the logging pack) has one line per log call: fixed, reported, ignored, skipped with the reason, or checked |
| why did apply take several rounds? | `.logast/reports/apply-trace.txt`: what each round applied and which orders its rescan brought up. Steps run in order (setup, structure, lines, mdc, messages, exceptions, levels, reports), usually one round each; a second pass applies nothing in a healthy run |
| `CHECK FAILED: … did not see its own fix` | a rule's order came back unchanged after its edit: apply applied it once and stopped there (instead of adding the same lines on every pass). A logast bug: please report the shape of the code (`logast shape .` exports it without your names) |
| `CHECK FAILED: … undo each other` | two fixes reverted each other; apply stopped. A logast bug: please report it with `.logast/reports/apply-trace.txt` |
| `N log call(s) recognised by name only` | the logger's type did not resolve (Lombok's `@Slf4j` without Lombok on the classpath): log lines are still recognised by name, but other types are probably missing too. Run `logast classpath .` |
| `--fail-on` exits with 4 | the analysis was incomplete (files that did not parse, no classpath); it must not pass CI. Fix the cause the warning names |
| an MDC key is scoped where you did not expect | `logast why . <key>`: every method that logs it, the decision and the reason |

## Known limitations

- Gradle builds only. Kotlin sources and reactive (WebFlux) code are not analysed.
- Calls into libraries end the call graph; whatever happens inside a library is not followed.
- `MDC.putCloseable` (it removes the key instead of restoring it) is rewritten to `MdcScope.of` only as the resource
  of a try-with-resources whose variable is otherwise unused; anywhere else it is reported (MDC_NOT_CLOSEABLE).
- LOG_OBJECT_ARGUMENT judges by type: a project class logged whole is reported unless it is a value record or defines
  `toString()`; library types are only flagged when they are known payloads (headers, requests, JSON, records).
- `LOG_VOLUME` is only as good as the traffic numbers you provide.
- AUDIT_MISSING follows direct calls only: an audit written by a listener of a published event counts when the event
  (or what it carries) is audit-typed, but not when the listener alone decides to audit.
- UNMAPPED_EXCEPTION treats a controller's own `@ExceptionHandler` as global, and ignores exceptions from libraries.

## Check a logast update against your own services (#10)

Keep your real services as regression cases on your machine only, outside git:

```
~/.logast-private/<service>/before/        the service as it was (a copy)
~/.logast-private/<service>/classpath.txt  optional: logast classpath writes .logast/classpath-*.txt
~/.logast-private/<service>/expected/      the reviewed migrated result
```

- `./gradlew :cli:privateRegression -Pprivate.update` records `expected/` (review it first: it is what logast
  produces today).
- After a logast update, `./gradlew :cli:privateRegression` migrates every `before/` again and names each file
  that differs (first differing line). Nothing leaves the machine; without the folder the task does nothing.
- `-Plogast.private.dir=<dir>` uses another folder.

