# Install

**From source** (needs a JDK 21+ on `PATH`):

```bash
git clone https://github.com/magyar-attila-317/logast.git && cd logast
./install.sh          # asks what is not set up yet: Gradle? the support library to Maven Local? to a repository? (-y: yes to the first two)
logast --version
```

**From a release:** download `logast-<version>.zip` from the [releases page](https://github.com/magyar-attila-317/logast/releases),
unzip it anywhere and put its `bin/` on `PATH` (or, from a checkout, `./install.sh --zip logast-<version>.zip`). How
releases are made: [releasing.md](releasing.md).

**With Gradle:** once `install.sh` has set up Gradle, `./gradlew logastApply` works in any Java build, with no edit to
the build ([gradle-plugin.md](gradle-plugin.md)).

| `install.sh` option | what it does |
|---|---|
| *(none)* | builds the CLI, installs it to `~/.local/share/logast` and links `~/.local/bin/logast` |
| `--skill` | installs the Claude Code skill to `~/.claude/skills/logast/SKILL.md`, so Claude Code knows the workflow |
| `--starter` | puts `dev.logast:logging-support` and `dev.logast:test-capture` into Maven Local (`~/.m2`) without asking; `apply` then adds the jar where a build resolves it |
| `--publish-to <name\|url>` | publishes them to a repository your Gradle knows (by name, with its credentials) or a URL, without asking ([starter.md](starter.md#publishing-it)) |
| `--zip <file>` | installs a release zip instead of building |
| `-y` / `--no-gradle` | `-y`: yes to the Gradle setup and to Maven Local without asking; `--no-gradle`: skips the Gradle setup (the plugin in Maven Local and an init script, so `./gradlew logastApply` works in any Java build, [gradle-plugin.md](gradle-plugin.md)) |
| `--ask` / `--force` | `--ask`: asks again what an earlier "no" skips; `--force`: rebuilds and reinstalls even when this commit is installed |

- `LOGAST_HOME` / `LOGAST_BIN` override the install locations.
- **Update:** pull and run `./install.sh` again. It replaces the installed copy, refreshes an installed skill and what
  it set up before (Gradle, the support library in Maven Local), and asks only about what is not set up. A "no" is
  remembered (`~/.config/logast/config.yaml`; `--ask` asks again). When the installed `logast --version` names this
  commit and the checkout has no changes, it builds nothing: `already installed — nothing to do`.
- **macOS:** install a JDK first (`brew install openjdk@21`, SDKMAN or Temurin); the `/usr/bin/java` stub alone is not
  a JDK. `~/.local/bin` is not on the macOS `PATH` by default: add `export PATH="$HOME/.local/bin:$PATH"` to
  `~/.zshrc`, or install elsewhere with `LOGAST_BIN=/opt/homebrew/bin ./install.sh`.

## Development

```bash
./gradlew build              # all modules and unit tests
./gradlew :cli:installDist   # cli/build/install/logast/bin/logast
./gradlew :cli:distZip       # release zip with docs, examples and the Claude Code skill
```

| module | contents |
|---|---|
| `core` | parsing, call graph and path context, policy loader, rules, work orders, apply, verify, fleet, review |
| `cli` | the `logast` command |
| `gradle-plugin` | the `dev.logast` Gradle plugin; TestKit tests: `./gradlew :gradle-plugin:functionalTest` |
| `logging-support` | the runtime starter used by generated code |
| `test-capture` | log capture added to test runs by `logast verify` |
| `fixtures/sample-app` | a shop app with planted gaps; `// expect: RULE` markers are checked exactly by the tests |

**End-to-end tests.** `e2e/before` is a small service with the code shapes real services showed. Each scenario runs
`logast apply` on a copy, compiles the result and compares every file and the printed output with a
snapshot in `e2e/expected*`.

```bash
./gradlew :cli:e2e --tests "*Name*"   # the scenarios a change touches (JDK 25)
./gradlew :cli:e2eJdk21               # the same on Java 21
./gradlew build -Pe2e                 # build plus both e2e tasks (CI runs them on Linux and macOS)
./gradlew :cli:e2e -Pe2e.update       # rewrite the snapshots after an intended change; review git diff e2e/
```

Rules for changes (every change comes with a test that fails without it, issues, the e2e matrix):
[AGENTS.md](../AGENTS.md).
