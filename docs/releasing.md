# Releasing

logast is versioned with [semantic versioning](https://semver.org), in the 0.x range for now: a **minor** release
(0.2.0) for new features or changed behaviour, a **patch** (0.2.1) for fixes only. The version lives in
`gradle.properties`; the CLI, the Gradle plugin, the starter and the coordinates the CLI writes all carry it (#24).

**When:** at the end of a milestone (a set of issues that together give users something new), not after every change.
Between releases, main moves on under the last released number.

**How** (#155), on a clean main that is pushed and green on CI:

```bash
scripts/release.sh 0.2.0
```

It sets the version in `gradle.properties` (one commit, `release: v0.2.0`), tags `v0.2.0` and pushes both. The
[release workflow](../.github/workflows/release.yml) then checks that the tag names the version, builds and tests,
and creates the GitHub release with the zip (`logast-0.2.0.zip`) and notes generated from the commits since the
previous tag. A version with a suffix (`0.3.0-rc.1`) becomes a pre-release.

It refuses: a version that is not `x.y.z`, uncommitted changes, a main that differs from origin/main, a tag that
exists, a commit whose CI is not green (`RELEASE_SKIP_CI=1` skips that check; the workflow still tests), and a version
without its section in [CHANGELOG.md](../CHANGELOG.md).

**Checklist** (#294):
- **Small and often**: a fix a team waits for is useless on main, a project depends on a release.
- **CHANGELOG.md**: move the Unreleased entries under `## x.y.z` before releasing. It lists what changes in the code
  logast writes (rule behaviour, defaults, generated text), then CLI changes; the release notes start with it.
- **The support library is published and resolvable**: the release workflow publishes `dev.logast:logging-support:x.y.z`
  to this repository's GitHub Packages; check it is listed
  (`gh api /users/<owner>/packages/maven/dev.logast.logging-support/versions`). Teams in dependency mode publish the
  same version into their own registry (`logast support publish --version x.y.z`).
- **Compatibility**: CLI x.y.z writes calls into logging-support x.y.z; a service in dependency mode moves both together
  (the CLI's support classes and its generated calls are one release).

**Installing a release:** download the zip from the [releases page](https://github.com/magyar-attila-317/logast/releases)
unzip it anywhere and put its `bin/` on `PATH` (or, from a checkout, `./install.sh --zip logast-<version>.zip`).
