# Gradle plugin and CI

The `dev.logast` Gradle plugin runs logast inside a service's own build: no installed CLI and no `logast classpath`
step, because the plugin hands logast the classpath the build has already resolved. It is meant for CI and for
everyone on the team who does not run the CLI. It can also migrate the code (`logastApply`); reviewing message texts
(`review`) and the log-capture check (`verify`) stay with the CLI.

## On your machine: nothing to add

`./install.sh` (from a checkout) offers to set Gradle up; answer `y`, or run `./install.sh -y`. It then:

- publishes `dev.logast:core`, `dev.logast:cli` and `dev.logast:gradle-plugin` to Maven Local (`~/.m2`);
- writes `${GRADLE_USER_HOME:-~/.gradle}/init.d/logast.gradle`, an init script Gradle runs for every build.

After that, `./gradlew logastApply` (and every other task below) works in any Java service, with no change to its
build:

- The plugin, and logast with it, is resolved only when you run a logast task; every other build on the machine
  resolves and runs nothing of logast. So `./gradlew tasks` does not list the logast tasks; `./gradlew help --task
  logastApply` does.
- It is applied to every project that has the `java` plugin. A build that applies `dev.logast` itself (below) keeps
  its own, with its own version and settings.
- Opt out for one build with `-Plogast.disabled`, or with the environment variable `LOGAST_DISABLED=1`; remove the
  init script to switch it off for good.
- install.sh asks before it changes anything (Enter = no). Without a terminal it skips the setup and says to rerun
  with `-y`; `--no-gradle` skips it without asking. It replaces `init.d/logast.gradle` only when that file is its own
  (the first line names logast); otherwise it warns and leaves it.
- A release zip (`install.sh --zip`) has no sources to publish from: no Gradle setup. Its `gradle/logast.gradle` is
  the same init script, for when the plugin is in your repository.

## For CI and the team: apply it in the build

CI machines have no init script in their Gradle home: apply the plugin in the build.

```kotlin
// settings.gradle.kts: where the plugin comes from (see "Publishing it" below)
pluginManagement {
    repositories {
        maven("https://nexus.example.com/repository/maven-releases/")   // or mavenLocal() to try it out
        gradlePluginPortal()
    }
}

// build.gradle.kts
plugins {
    java   // (a Spring Boot service already has it)
    id("dev.logast") version "0.1.0"
}

logast {                                        // all optional; these are the defaults
    failOn = "medium"                           // logastCheck fails on new findings of this severity or higher
    baseline = file(".logast/baseline.json")    // what logastCheck compares with: commit it
    format = "summary"                          // what logastScan prints
    maxHeapSize = "2g"                          // heap of the scan's worker process
}
```

| task | what it does | the CLI equivalent |
|---|---|---|
| `logastScan` | scans the project, writes `.logast/reports/orders.json` and the decision files, prints a summary | `logast scan . --format summary` |
| `logastBaseline` | records today's findings in the baseline (commit the file) | `logast scan . --update-baseline` |
| `logastCheck` | the CI gate: fails on findings that are not in the baseline at or above `failOn`, naming each | `logast scan . --baseline --fail-on medium` |
| `logastSarif` | writes the findings as SARIF to `build/reports/logast/logast.sarif`, for code scanning | `logast scan . --format sarif > logast.sarif` |
| `logastApply` | applies every fix that needs no message text to the sources; `--preview` (or `-PlogastDryRun`) only prints the diff | `logast apply . --auto --write` |

- One-off overrides: `./gradlew logastCheck --fail-on high`, `./gradlew logastScan --format human`.
- `logastCheck` also fails when there is no baseline yet (it says to run `logastBaseline`) and when the analysis is
  incomplete (files that did not parse, no classpath): a broken analysis finds less and must not pass.
- A failing check lists the new findings:

```
> logast: 2 new findings at or above medium (not in the baseline /work/orders/.logast/baseline.json):
    SIL-80d0f8  medium  ENTRYPOINT_SILENT  src/main/java/demo/PaymentController.java:10  PaymentController.pay
        POST /payments: its own flow logs nothing at INFO (shared code deeper down and catch blocks do not count)
    SWA-776e5d  medium  SWALLOWED_EXCEPTION  src/main/java/demo/PaymentController.java:13  PaymentController.pay
        RuntimeException is caught and dropped without a log line in PaymentController.pay (falls back silently)
  Fix them (`logast scan .` shows each in full), or record them as known with `./gradlew logastBaseline`.
```

### Adopt in one step

```bash
./gradlew logastApply      # migrates the project's sources with the build's classpath
git diff                   # review; one diff per kind of change in .logast/reports/steps/
```

- `./gradlew logastApply --preview` (or `-PlogastDryRun`) prints the diff and changes nothing. (Not `--dry-run`:
  that is Gradle's own flag, which skips every task.)
- The task fails when apply rejects a fix (the `REJECTED` lines say why; the other fixes are applied), when apply
  cannot run, and on `CHECK FAILED` (the result breaks a migration invariant: a logast bug, please report it).
- When apply adds dependencies to the build, it does not run Gradle again from inside the build to refresh the
  classpath: run `./gradlew logastApply` once more, so the new dependencies' types are resolved.
- Fixes that need a message text wait for `.logast/decisions/messages.yaml`, as with the CLI; apply's output lists
  what is left.

### What it scans

- The project the plugin is applied to: its folder is the root logast scans, its `compileClasspath` the classpath. The
  tasks appear once the `java` plugin is applied.
- In a multi-module build, apply it to each module you want gated; each gets its own `.logast/` and baseline. (The
  CLI's `logast scan .` on the repository root covers all modules at once.)
- `logast.yaml`, `.logast/decisions/` and the baseline are read as with the CLI. The tasks are never up to date,
  since they read those files too.

### How it runs logast

The plugin carries logast itself (the `cli` and `core` jars and OpenRewrite) as its dependencies, so the plugin version
is the logast version. Each task runs logast in a Gradle worker process with the plugin's classpath:

- the scan gets its own heap (`maxHeapSize`) and nothing of it stays in the build's daemon;
- logast needs Java 21 or later. The plugin classes themselves load on Java 17, so a build whose Gradle runs on 17
  can still apply it and point the scan at a newer JVM:

```kotlin
logast { javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) } }
```

- the tasks are compatible with the configuration cache.

### Publishing it

The plugin is not on the Gradle Plugin Portal. Publish it with the jars it depends on to the repository your builds
resolve plugins from, with the same properties as [the starter](starter.md#publishing-it):

```bash
./gradlew :core:publish :cli:publish :gradle-plugin:publish \
    -PrepoUrl=https://nexus.example.com/repository/maven-releases/ -PrepoUser=… -PrepoPassword=…
# Maven Local, for trying it out: ./gradlew :core:publishToMavenLocal :cli:publishToMavenLocal :gradle-plugin:publishToMavenLocal
```

This publishes `dev.logast:core`, `dev.logast:cli`, `dev.logast:gradle-plugin` and the plugin marker
`dev.logast:dev.logast.gradle.plugin`, which `plugins { id("dev.logast") version "…" }` looks up. The plugin's
other dependencies (OpenRewrite, Jackson, picocli) come from Maven Central, so the repository must proxy it.

## GitHub code scanning (PR annotations)

GitHub shows SARIF findings as annotations on the pull request's diff and in the repository's Security tab. logast
writes SARIF (`--format sarif`, or the plugin's `logastSarif`); `github/codeql-action/upload-sarif` uploads it. The
upload needs `security-events: write`, and code scanning must be available for the repository (public repositories,
or private ones with GitHub Advanced Security).

With the Gradle plugin, `.github/workflows/logging-standard.yml`:

```yaml
name: logging-standard
on: pull_request
jobs:
  logast:
    runs-on: ubuntu-latest
    permissions: { contents: read, security-events: write }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '21' }
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew logastSarif
      - uses: github/codeql-action/upload-sarif@v3
        if: always()
        with: { sarif_file: build/reports/logast/logast.sarif, category: logast }
      - run: ./gradlew logastCheck          # .logast/baseline.json is committed
```

With the CLI instead (a release zip from your repository, then `logast classpath .`), the same job is in
[examples/github-actions.yml](../examples/github-actions.yml); it writes the SARIF file with
`logast scan . --format sarif > logast.sarif` (there with `--since origin/<base branch>`: only findings on changed
lines).

- Every finding is a SARIF result: severity high is an `error`, medium a `warning`, low a `note`; the rule id is the
  logast rule, and the order id is the result's fingerprint (`logastOrderId`), so GitHub keeps one alert per finding
  across runs.
- An incomplete analysis marks the SARIF run as not successful, with the reason as a tool notification.
- Annotations appear on the lines a pull request changed; the full list is under Security → Code scanning.
- SARIF locations are relative to the scanned folder, which GitHub reads as relative to the repository root. Apply
  the plugin (or run the CLI) on the repository root for the annotations to land on the right files; a module in a
  subfolder gets paths without its folder, so for a multi-module repository run the CLI on the root.
