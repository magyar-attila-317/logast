# Changelog

What changes in the code logast writes, per release (#294). Rule behaviour, defaults and generated text come first;
CLI changes after them. Unreleased entries move under the version when it is released (scripts/release.sh checks).

## Unreleased

**What logast writes**
- Callers' traces continue by default again (`entrypoints.trustInboundTrace: true`): the propagation lockdown (`consume: []`, `produce: [w3c]`, no baggage) is written only for a service that sets it to false (#270).
- An exception policy: `logging.exceptionStack: never` / `exceptionMessage: never` log the caught exception's class only (`[errorType=…]`) (#278).
- `logging.structuredProfiles: [prod, …]`: the JSON console format goes into those profiles' config only; dev, test and local keep plain logs (#278).
- Code that withholds an exception on purpose (a "deliberately" comment, a redacting helper) is reported, not changed (#290).
- AUDIT_RECORD_SECRET no longer reports an event's source, a Spring bean or collaborator fields (`passwordEncoder`); AUDIT_BEFORE_COMMIT no longer counts listeners routed by a SpEL `condition` as an early write (#282).
- The order adding a GitHub Packages repository says a token is needed even to read, and names workflows without `packages: read` (#277).
- Setup offers the service's own `@ResponseStatus(4xx)` exceptions as `levels.clientErrors` (#279).
- `messages.yaml` asks for at most `logging.wordingQueue` (50) texts at once, the most severe first (#292).

**CLI**
- `logast --version` names the release tag in a release zip (it named the previous tag, #302).
- `apply --path <glob>`: one batch of a big service; REJECTED or CHECK FAILED exits 1 (#288).
- A test the change breaks (a changed signature) is a CHECK FAILED line naming it (#279).
- One progress line per step off a terminal; one apply per project at a time; a note when a run was killed (#293).

## 0.5.0

**What logast writes**

**Behaviour and trust: proposed, not changed** — a pack no longer changes what a request returns, what the service trusts or what callers see unless the team chose it.
- HTTP client and database query timeouts are written only when `logast.yaml` (or a policy it extends) sets `resilience.httpConnectTimeout` / `httpReadTimeout` / `dbQueryTimeout`; otherwise the finding proposes the value (#271).
- Adding the tracing starter keeps the service's trust in callers: `management.tracing.propagation.consume: []`, `produce: [w3c]`, `baggage.enabled: false`, unless `entrypoints.trustInboundTrace: true` or the service configures propagation itself (#270).
- The trace id in error bodies (`logging-support.problem-trace-id.enabled`) is proposed, not switched on; setting the property (true or false) decides it (#291).

**Fewer, better lines**
- A write endpoint gets a "… started" line only when its rate is known and at most `levels.addedInfoMaxPerDay` (1000/day); a repository save/delete line is INFO only on paths known to be that quiet, else DEBUG (#285).
- `traffic.yaml` takes patterns (`"* /api/admin/**": 10`; own value > schedule > most specific pattern) (#279, part).
- A per-item line that reports a state change or a mismatch ("status changed: … -> OFFLINE", "price discrepancy") keeps its level and is reported instead of lowered (#286).
- A startup listener is observed as the work it starts (named after it); one that only sets up is not; frequent sweeps log no INFO per run (#272).

**Meaning kept**
- An MDC `traceId` that holds the service's own id (a correlation id from a header) is renamed to `correlationId` in every put, remove and get, not deleted; a value from a parameter or field is kept and reported (#269).
- A repository save inside a return expression is logged before the return (`var saved = …; log…; return saved.getId();`) (#284).
- A metrics catalog whose simple name exists in several packages is called by its full name (#274).

**CLI**
- `apply --skip lines,levels`: leave steps out; the others apply and compile (#287).
- The compile gate runs javac's flow analysis even on a partial classpath (unreachable code, a missing return) (#284).
- `--dry-run` writes nothing, decision files included; a `--pack` run keeps other packs' decision entries (#275).
- Killed runs: the scratch copy is removed (shutdown hook, or the next run's sweep) (#283).


**Compatibility**
- CLI 0.5.0 writes calls into `dev.logast:logging-support:0.5.0`; services in dependency mode publish that version into their registry (`logast support publish --version 0.5.0 --repo github.com/<owner>/<repo>`).
- E2e snapshots changed for #270, #271, #272, #285, #291 (reviewed per change in the commits).
