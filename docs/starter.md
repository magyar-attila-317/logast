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
| when | the default while the service's build cannot resolve the starter, or the service must not take new dependencies | the default once the service's build resolves the starter (published with `install.sh`), or once the policy, or a company policy it extends, sets `support.repository` or `support.coordinates` |

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

`./install.sh` asks (Enter = no):

- **Maven Local** (`~/.m2`): builds on this machine that list `mavenLocal()` resolve it. `--starter` or `-y` say yes
  without asking.
- **A remote repository**: install.sh lists the Maven repositories this machine's Gradle already configures (logast's
  own build with your `~/.gradle/init.d` scripts; Maven Central, the Plugin Portal and Maven Local left out), then
  `another URL`:

  ```
    Publish the support library to a repository too? Then every build that resolves from there gets the jar:
      1) acme-releases            https://nexus.example.com/repository/maven-releases/ (hosted: takes uploads)
      2) acme-public              https://nexus.example.com/repository/maven-public/
      3) another URL
    Choose [1-3, Enter = no]
  ```

  A known repository is published to with the credentials configured on it; another URL takes `REPO_USER` /
  `REPO_PASSWORD` or asks (the password hidden). `--publish-to <name|url>` answers without asking. A refused upload
  is one line (401: credentials, 403: not allowed, 405: no uploads there, a group or proxy repository; 400: the
  version is there already) and install.sh exits non-zero; the command is installed either way.

Then `logast apply` wires it in wherever the service's build resolves it (no repository line, no policy change:
[configuration.md](configuration.md#support-the-runtime-helper-library)).

The same without install.sh:

```bash
./gradlew -q logastRepositories                              # the repositories install.sh offers: name, url
./gradlew :logging-support:publishAllPublicationsToCompanyRepository :test-capture:publishAllPublicationsToCompanyRepository \
    -PrepoName=acme-releases                                   # a known one, with its credentials
./gradlew :logging-support:publish :test-capture:publish \
    -PrepoUrl=https://nexus.example.com/repository/maven-releases/ -PrepoUser=… -PrepoPassword=…
# or REPO_URL / REPO_USER / REPO_PASSWORD in the environment (CI)
```

To pin other coordinates in the policy: `support.coordinates: com.example:logging-support:<version>`. The version
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

