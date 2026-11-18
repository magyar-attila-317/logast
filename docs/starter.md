# The runtime starter (`logging-support`)

Generated code calls a small Spring Boot starter instead of repeating plumbing in every service: `ObservedContext`
(observed entrypoints), `MdcScope` (MDC that restores what it changed), `ContextExecutors` and a
context-propagating `TaskDecorator` (MDC and trace across threads), `TraceContextEnv` (trace into launched
processes), `AfterCommit`, `Outcomes` (counting), `ProblemTraceId`, MDC hygiene and ECS log defaults.

**On its own the starter switches nothing on.** Each behaviour has a property that is off unless `true`; logast
writes it to `application.yml` for the pack that uses it.

## How a service gets it

| | `support.mode: dependency` (default) | `support.mode: vendor` |
|---|---|---|
| what logast does | adds `dev.logast:logging-support` to `build.gradle(.kts)` | copies its classes into `<app package>.logging` |
| upgrades | bump the version | re-copy, or switch to the dependency later |
| when | you publish the starter to your repository | no shared artifact yet, or the service must not take new dependencies |

Both give the same generated code. In vendor mode, copied classes from an older logast are updated by an AUTO order
(the dry run shows the diff). `logast init --support vendor` writes the mode into `logast.yaml`; the other settings
(`support.coordinates`, `support.vendorPackage`) are in [configuration.md](configuration.md#support-the-runtime-helper-library).

A pack may add the starter for one class without changing what another pack owns: the errors pack alone adds it for
`ProblemTraceId` (in vendor mode it copies only that class), and does not switch on the log format.

## Properties

| property | behaviour | written by |
|---|---|---|
| `logging-support.log-defaults.enabled` | logs default to ECS JSON with `SERVICE_NAME` / `APP_VERSION` (a format the service sets wins) | logging |
| `logging-support.mdc-hygiene.enabled` | `MdcHygieneFilter` clears and reports MDC that leaks across requests (servlet apps) | logging |
| `logging-support.context-propagation.enabled` | the `TaskDecorator` carries MDC and trace context into `@Async` and Spring executors | logging, tracing |
| `logging-support.batch-trace.enabled` | batch apps continue the trace from `TRACEPARENT` / `TRACE_ID` | tracing |
| `logging-support.problem-trace-id.enabled` | `"traceId"` in error responses | errors |

Upgrading a service that took an earlier starter, where the first four were on by default: run `logast apply` with
its packs (logging and tracing by default) and it writes the properties, or set them yourself. A value the service
already sets, `true` or `false`, is left as it is.

## Publishing it

To Maven Local (`~/.m2`), for trying it out: `./install.sh --starter`.

To your company repository:

```bash
./gradlew :logging-support:publish :test-capture:publish \
    -PrepoUrl=https://nexus.example.com/repository/maven-releases/ -PrepoUser=… -PrepoPassword=…
# or REPO_URL / REPO_USER / REPO_PASSWORD in the environment (CI)
```

Then point services at it in their policy: `support.coordinates: com.example:logging-support:<version>`. The version
comes from `gradle.properties`. `test-capture` is the jar `logast verify` adds to test runs.
