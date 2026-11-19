# The runtime starter (`logging-support`)

Generated code calls a small Spring Boot starter instead of repeating plumbing in every service: `ObservedContext`
(observed entrypoints), `MdcScope` (MDC that restores what it changed), `ContextExecutors` and a
context-propagating `TaskDecorator` (MDC and trace across threads), `TraceContextEnv` (trace into launched
processes), `AfterCommit`, `Outcomes` (counting), `ProblemTraceId` (`ReactiveProblemTraceId` on WebFlux), MDC hygiene and ECS log defaults. One class only
your own code calls: `BusinessBaggage` (a business id as trace baggage, see
[tracing](packs/tracing.md#business-ids-across-services-baggage)); in vendor mode it is copied once your code uses it.

**On its own the starter switches nothing on.** Each behaviour has a property that is off unless `true`; logast
writes it to `application.yml` for the pack that uses it.

## How a service gets it

| | `support.mode: vendor` (default) | `support.mode: dependency` |
|---|---|---|
| what logast does | copies its classes into `<app package>.logging` | adds `dev.logast:logging-support` to `build.gradle(.kts)` |
| upgrades | re-copy, or switch to the dependency later | bump the version |
| when | no shared artifact yet, or the service must not take new dependencies | you publish the starter to your repository (the default once the policy, or a company policy it extends, sets `support.repository` or `support.coordinates`) |

Both give the same generated code. In vendor mode, copied classes from an older logast are updated by an AUTO order
(the dry run shows the diff). `logast init --support vendor` writes the mode into `logast.yaml`; the other settings
(`support.coordinates`, `support.vendorPackage`) are in [configuration.md](configuration.md#support-the-runtime-helper-library).

A pack may add the starter for one class without changing what another pack owns: the errors pack alone adds it for
`ProblemTraceId` (in vendor mode it copies only that class, `ReactiveProblemTraceId` on a WebFlux-only service), and does not switch on the log format.

## Properties

| property | behaviour | written by |
|---|---|---|
| `logging-support.log-defaults.enabled` | logs default to ECS JSON with `SERVICE_NAME` / `APP_VERSION` (a format the service sets wins) | logging |
| `logging-support.mdc-hygiene.enabled` | `MdcHygieneFilter` clears and reports MDC that leaks across requests (servlet apps) | logging |
| `logging-support.context-propagation.enabled` | the `TaskDecorator` carries MDC and trace context into `@Async` and Spring executors | logging, tracing |
| `logging-support.batch-trace.enabled` | batch apps continue the trace from `TRACEPARENT` / `TRACE_ID` | tracing |
| `logging-support.problem-trace-id.enabled` | `"traceId"` in error responses: `ProblemTraceId` (Spring MVC: ProblemDetail and Boot's error map), `ReactiveProblemTraceId` (WebFlux: ProblemDetail, through an `ErrorResponse.Interceptor`; needs `spring.reactor.context-propagation: auto`) | errors |

Upgrading a service that took an earlier starter, where the first four were on by default: run `logast apply` with
its packs (all of them by default) and it writes the properties, or set them yourself. A value the service
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

### Releases and versions

- The version lives in one place, `gradle.properties` (`version=…`): the starter jar, `logast --version` and the
  coordinates logast writes by default (`dev.logast:logging-support:<that version>`) all carry it.
- A release: raise `version`, tag the commit `v<version>` (the `release-check` workflow fails when tag and version
  differ), then `./gradlew publish -PrepoUrl=…` to the repository your services resolve from (company Nexus, GitHub
  Packages, any Maven repository; Maven Local without `-PrepoUrl`).
- A service that pins an older starter (`support.coordinates: …:<older>`) in dependency mode gets a warning from
  `scan`, `apply` and `logast explain policy . --check`: generated code may call members the older release does not have.
  Pin the version of the logast you run, or use `support.mode: vendor` (the classes are copied into the service).

