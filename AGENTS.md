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

## Every change comes with a test that fails without it
- A new rule or fix gets a case in an e2e scenario (`e2e/before`, snapshots in `e2e/expected*`) that compares **every
  file** of the result. Put the code shape that showed the problem into the scenario, with neutral names.
- Switch the change off once and check that its test fails. A test that passes without the fix proves nothing.
- Every scenario must keep its properties: one pass (pass 2 of the first apply applies nothing), a second apply changes
  nothing, no `CHECK FAILED`, and the result compiles. Do not weaken these assertions to make a change pass.
- Run the e2e tests under the environments they cover: JDK 21 (`e2eJdk21`) and 25, with and without Lombok, with class
  folders on the classpath. Results must be identical.
- `./gradlew :cli:test -Pe2e.update` rewrites the snapshots: review `git diff e2e/` before keeping them, and say in the
  commit why the expected output changed.

## Before pushing
- `./gradlew build` must pass, and in a clean checkout of HEAD plus the staged changes too (an ignored file once kept a
  snapshot out of git).
- Don't filter build output so much that compile errors disappear.

## How to investigate
- Measure before redesigning: `.logast/apply-trace.txt` shows what each apply round did and what its rescan brought up;
  `LOGAST_TIMING=1` prints scan times; `logast why . <key>` explains MDC placement.
- When behaviour differs between machines, suspect the environment first (classpath content, JDK version, Lombok,
  class folders), and reproduce it in a variant of an e2e test.
- Check framework behaviour in the official documentation (Spring Boot properties, observation support) instead of
  assuming it, and cite the source in the issue.
