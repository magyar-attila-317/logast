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

1. Publish it to your repository: `./install.sh` offers the repositories your Gradle already knows (with their
   credentials) or another URL; `./install.sh --publish-to <name|url>` without asking. By hand:

   ```bash
   ./gradlew :logging-support:publish :test-capture:publish \
       -PrepoUrl=https://nexus.example.com/repository/maven-releases/ -PrepoUser=… -PrepoPassword=…
   # or -PrepoName=<a repository from ./gradlew -q logastRepositories>, with the credentials configured on it
   ```

   That alone is enough for services whose build resolves from there: `apply` checks it with the service's own
   repositories and adds the jar instead of copying the classes in, with no repository line
   ([configuration.md](configuration.md#support-the-runtime-helper-library)).

2. Or set it once in the company policy; every service that extends it switches:

   ```yaml
   support:
     mode: dependency
     coordinates: com.example:logging-support:<version>
   ```

   A `support.repository` or `support.coordinates` alone also selects dependency mode. In this mode, and only in it,
   `apply` lists "check the starter resolves" after it adds the dependency.

### From GitHub Packages

Every logast release also publishes `dev.logast:logging-support` and `dev.logast:test-capture` to GitHub Packages
(`https://maven.pkg.github.com/<owner>/logast`, the release workflow), so a service can depend on the released starter
without a company repository:

```yaml
support:
  mode: dependency
  coordinates: dev.logast:logging-support:<version>
  repository: https://maven.pkg.github.com/<owner>/logast
```

GitHub Packages wants a token even for a public package. `apply` writes the repository with GitHub's credentials
convention:

- on a developer's machine: `gpr.user` (the GitHub user) and `gpr.key` (a token with `read:packages`) in
  `~/.gradle/gradle.properties`;
- in a GitHub Actions workflow: `GITHUB_ACTOR` and `GITHUB_TOKEN` (pass them as `env:` to the Gradle step), after the
  package's owner grants the service's repository read access in the package settings (*Manage Actions access*). A
  private service repository can then read it with its own workflow token, no personal token in CI.

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
- A [named fleet](fleet.md#the-fleets-companyyaml) has its own `company.yaml`, which its members extend by the fleet's
  name: `logast fleet create` writes it with the usual company settings commented out, and logast adds commented
  suggestions with counts from what the members use ("2 of 2 members use it: uncomment to make it the company's").
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
