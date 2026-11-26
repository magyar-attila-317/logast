# Working on logast

Rules of thumb for anyone (person or agent) changing this repo. They come from what went wrong while debugging logast
on real services; the background is in issue #7.

## Work goes through GitHub issues
- Before starting, check the open issues (`gh issue list`) and whether the one you pick still applies to the current
  code. Mark issues you could not re-check with the `verify-first` label.
- Every finding gets an issue: a bug seen on a real service, a gap noticed while working, a measured result. Write down
  what you know (cause, evidence, what was ruled out), so the work can be picked up later.
- Update the issue as you go (cause found, approach, results), reference it in the commit (`Fixes #n`), and close it with
  a comment saying what was done and which test covers it.
- This repository is public: no company names, company code or internal identifiers in issues, commits, tests or
  fixtures. Anonymised exports (`logast shape`) of real services stay local and never get committed.
- No links to chat or agent sessions (`claude.ai/…`, `Claude-Session:` trailers, other tools' share links) in commits,
  PRs or issues: those sessions hold the real code and screenshots this repository must not point to. Attribution
  is a plain `Co-Authored-By:` line.

## Every change comes with a test that fails without it
- A new rule or fix gets a case in an e2e scenario (`testing/e2e/before`, snapshots in `testing/e2e/expected*`). Put the code shape
  that showed the problem into the scenario, with neutral names. What a scenario snapshots (#110):
  - **every file logast changes in the service** (`src/`, resources, build files), compared in full;
  - **its findings as a list** in `FINDINGS.txt`: `KIND RULE file:line` per finding left after the migration (a
    report-only finding is pinned by rule and place, not by its wording);
  - **logast's own output in full** (`.logast/**`, `OUTPUT.txt`, finding texts) only in the few scenarios listed in
    `FULL_REPORTS` of `E2eGoldenTest`, and single report or decision files a scenario is about (`KEEP`).
  A change of report wording, ids or the `.logast` layout is reviewed in those few scenarios; a rule change shows in
  every scenario whose code it changes. Assert the texts that matter for a new finding in the scenario itself.
- Switch the change off once and check that its test fails. A test that passes without the fix proves nothing.
- Every scenario must keep its properties: one pass (pass 2 of the first apply applies nothing), a second apply changes
  nothing, no `CHECK FAILED`, and the result compiles. Do not weaken these assertions to make a change pass.
- The e2e scenarios run in their own tasks (#73): `./gradlew :cli:e2e` (JDK 25) and `:cli:e2eJdk21`, each with and
  without Lombok and with class folders on the classpath, as eight shards (`E2eShard0..7`) in parallel JVMs (one CI job each). Results
  must be identical. Locally, run the scenarios your change touches (`./gradlew :cli:e2e --tests "*Name*"`); CI runs the e2e matrix on Linux for every push, and on macOS nightly after a day with commits (or with Run workflow).
- `./gradlew :cli:e2e -Pe2e.update` rewrites the snapshots (add `--tests` to limit it): review `git diff testing/e2e/` before
  keeping them, and say in the commit why the expected output changed. A change of shared code (rule output that
  edits code, or a finding's rule or place) can touch every scenario: then update and run all of them
  (`./gradlew build -Pe2e`).

## Where knowledge belongs (discover, derive, map)
- logast's extractors read what code and configuration state through documented conventions (Spring, Quartz, Flyway,
  GitHub Actions, Helm, Terraform), by following the code, not by guessing from names or key shapes. One team's
  layout (its tables, its keys, its conventions) belongs in that team's mappings, never in an extractor.
- A request that only fits one team's setup: add the raw fact or the generic mapping capability that is missing, and
  give the team the mapping. The layers and the rules are in [docs/architecture.md](docs/architecture.md) (#215).

## Documentation
- README.md is the stable overview (what logast is, install, quick start, workflow, packs in a line each, pointers).
  Rule tables, per-rule details, property tables and anything that drifts (counts, timings, version lists) belong in
  `docs/` (`docs/packs/<pack>.md`, `docs/rules.md`, `docs/starter.md`, `docs/configuration.md`): a new or changed rule
  updates its pack page, not the README.
- `docs/examples.md` shows real before/after code from the e2e scenarios: when what `logast apply` writes changes,
  update the affected snippet; a new editing rule adds an example there.
- Issues and PRs use the templates in `.github/` (their checklists repeat these rules).

## Before pushing
- `./gradlew build` (unit tests, a few seconds) and the targeted e2e scenarios must pass. CI builds every push from a
  clean checkout with the full e2e matrix (an ignored file once kept a snapshot out of git): check its result after
  pushing (`gh run watch`) and fix a red build first.
- Don't filter build output so much that compile errors disappear.

## Releases
- Release at the end of a milestone, not after every change: `scripts/release.sh <x.y.z>` on a clean, pushed, green
  main ([docs/releasing.md](docs/releasing.md)).

## How to investigate
- Measure before redesigning: `.logast/apply-trace.txt` shows what each apply round did and what its rescan brought up;
  `LOGAST_TIMING=1` prints scan times; `logast explain mdc <key>` explains MDC placement.
- When behaviour differs between machines, suspect the environment first (classpath content, JDK version, Lombok,
  class folders), and reproduce it in a variant of an e2e test.
- Check framework behaviour in the official documentation (Spring Boot properties, observation support) instead of
  assuming it, and cite the source in the issue.
