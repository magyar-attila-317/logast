# For platform teams

A single service needs none of this: `logast apply .` (or `./gradlew logastApply`) sets itself up, copies the support
classes in and writes a finished, compiling migration. This page is for the team that owns the standard across many
services: one starter, one company policy, one vocabulary, one report and one CI gate.

| what | why | details |
|---|---|---|
| [publish the starter](#publish-the-starter-and-switch-to-dependency-mode) | services depend on one versioned library instead of carrying a copy | [starter.md](starter.md) |
| [a company policy](#a-company-policy-companyyaml-and-extends) | one `company.yaml` every service `extends:` | [vocabulary.md](vocabulary.md#layers) |
| [the central vocabulary](#the-central-vocabulary) | one key per business id across services | [vocabulary.md](vocabulary.md#one-vocabulary-across-services) |
| [fleet](#fleet) | compliance and trace breaks across services | [commands.md](commands.md#fleet) |
| [the CI gate](#the-ci-gate) | fail a build only on new findings; PR annotations | [gradle-plugin.md](gradle-plugin.md) |

## Publish the starter and switch to dependency mode

By default (`support.mode: vendor`) logast copies the `logging-support` classes into each service. To have services
depend on the starter instead:

1. Publish it to your repository:

   ```bash
   ./gradlew :logging-support:publish :test-capture:publish \
       -PrepoUrl=https://nexus.example.com/repository/maven-releases/ -PrepoUser=… -PrepoPassword=…
   ```

2. Set it once in the company policy; every service that extends it switches:

   ```yaml
   support:
     mode: dependency
     coordinates: com.example:logging-support:<version>
   ```

   A `support.repository` or `support.coordinates` alone also selects dependency mode. In this mode, and only in it,
   `apply` lists "check the starter resolves" after it adds the dependency.

Versions, properties and the two modes side by side: [starter.md](starter.md).

## A company policy: `company.yaml` and `extends`

A service's `logast.yaml` can build on shared layers; it only writes what differs:

```yaml
extends: [https://git.example.com/platform/observability/raw/main/company.yaml]

logging:
  rewriteExistingMessages: false   # legacy service: fix structure, keep the wording
```

- Layers, merge order and overrides: [vocabulary.md](vocabulary.md#layers); `logast explain policy .` prints the merged
  result and every override.
- A complete company layer: [examples/restaurant-company.yaml](../examples/restaurant-company.yaml).
- Every setting: [configuration.md](configuration.md).

## The central vocabulary

Services drift apart (`orderId`, `order_id`, `order.id`). The central vocabulary collects every service's keys in one
worksheet outside the repos, you decide per key, and logast writes `company.yaml` from the decisions:

```bash
logast vocabulary --central ~/.logast services/billing services/shipping …   # harvest, then decide in the worksheet
logast vocabulary --central ~/.logast services/billing services/shipping …   # again: writes ~/.logast/company.yaml
logast fleet --vocabulary --central ~/.logast services/*/                    # what apply would rename, per service
```

Services then `extends:` company.yaml, and `logast apply` renames aliases in the code (MDC_KEY_ALIAS). The worksheet
format, the evidence, areas and pruning: [vocabulary.md](vocabulary.md#one-vocabulary-across-services).

A single service does not need this: `apply` collects the keys the service already uses into its own `logast.yaml`
(`logast vocabulary .` lists the ones still missing).

## Fleet

```bash
logast fleet services/*/ --out fleet-report   # fleet-report/fleet.html, with the trend against the last run
```

A compliance score per service, calls whose trace stops between two services, and one outcome counted under several
metric names. Options and output: [commands.md](commands.md#fleet).

## The CI gate

On a developer machine `./gradlew logastApply` needs no edit to the build (`install.sh` sets up an init script). CI
has no init script: apply the plugin in the build.

```kotlin
plugins {
    id("dev.logast") version "<version>"
}
```

- `./gradlew logastCheck` fails the build on findings at or above a severity that are not in the baseline
  (`.logast/baseline.json`, recorded with `logast scan . --update-baseline`).
- `./gradlew logastSarif` writes SARIF for PR annotations (GitHub code scanning).
- Without the plugin: `logast scan . --baseline --fail-on high --format summary`, exit codes in
  [commands.md](commands.md#scan).

Where the plugin comes from, its settings and a complete GitHub Actions job: [gradle-plugin.md](gradle-plugin.md).
