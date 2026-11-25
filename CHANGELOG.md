# Changelog

What changes in the code logast writes, per release (#294). Rule behaviour, defaults and generated text come first;
CLI changes after them. Unreleased entries move under the version when it is released (scripts/release.sh checks).

## Unreleased

**Findings**
- MDC_VALUE_UNVALIDATED (logging): an MDC value taken from a request header, a `@RequestHeader` / `@CookieValue` parameter or a query parameter with nothing that checks it first is reported (#278).

**Setup**
- The commented `levels.clientErrors` example also lists the exceptions an `@ExceptionHandler` answers with a 4xx (`@ResponseStatus`, a `ResponseEntity` / `ProblemDetail` with a 4xx status) (#279).
- traffic.yaml offers a commented pattern for each group of three or more endpoints without a rate under one path prefix (`"* /pledges/**"`): one value per group instead of one per endpoint (#279).

**CLI**
- `apply --commit-per-step`: one commit per pipeline step that changed something (then one for what later passes add); each compiles, so a step is dropped with one `git revert` (#288).
- `apply --until <step>` before the messages step no longer reports an id in both MDC and the message as CHECK FAILED: the messages step takes it out of the text.

## 0.5.4

**Log inventory**
- `.logast/inventory.jsonl`: every log call with an id that lasts across runs, whether logast added it, who last changed its text (logast, a person, generated wording) and a short history; a line keeps its id when lines are added above it or its text changes (#281).

**What logast writes**
- Counters added by default carry no `// logast:metric review` comment in the code: apply lists them, with how sure each name is, in `.logast/reports/metrics-review.txt` (#273).
- Call sites of one shape (three or more outcomes with the same event and last word in a package) share one metric with a `kind` tag (`Outcomes.countKind`) instead of a name each; an outcome the service's own metrics helper already counts gets no second counter (#273).
- Suffix keys say what the value is: two values the vocabulary maps to one key keep their own names and are not taken for the MDC value; `token.getUser().getId()` → `userId`; `kind.toLowerCase(…)` → `kind`; `e.getClass().getSimpleName()` → `errorType`. A name ending in Id/Uuid (`tokenDeviceId`) is not personal data.
- A text whose subject already says "failed" is not rephrased ("… failed with an HTTP error" stays).
- A catch in a loop that records the case and moves on (a rejected import row) gets no WARN line of its own.
- A noun left at the end of a sentence when its id moves to MDC goes with its preposition ("… for tenant" no longer dangles).
- A started line in a method whose parameters wrap gets the body's indent (#241).

**CLI**
- The launcher lets the heap grow to a third of the machine (was half), and a run that runs out of heap says how to give it more or run less at once, instead of dying silently next to other JVMs (#276).
- `apply --safe` holds back the source files whose changes go beyond logging in classes no test mentions, applies the rest, and lists what it held back (#316).
- Apply says how many log lines no longer carry their ids in the text (they are in MDC now), so tests asserting ids in log messages can be found (#315).

## 0.5.3

**What logast writes**
- SCHEDULED_WITHOUT_LOCK counts a job that runs its work through the team's own lock (`jobLock.runExclusive(TASK, this::run)`, ShedLock's `executeWithLock`) as locked (#298).
- UNBOUNDED_EXECUTOR leaves out a single-thread executor fed only through a compare-and-set guard (at most one task queued) (#299).
- The JSON log format is detected from the service's configuration without its comments; a logback file with its own JSON encoder (LogstashEncoder, …) owns the format: `structuredFormat: none`, no Boot property written next to it (#296).
- Code added at the top of a method whose parameters wrap is indented like the body, not like the wrapped parameter line (#241).
- A line that states an item's outcome ("Completed order {} …", "Archived {} …") keeps INFO in a loop over query data and is reported, not lowered (#309).
- A repository write gets no line of its own when every path to it already logs at INFO (#318); its level stays rate-based (#285).
- A catch that handles the outcome (a fallback value, a skip, a report entry) accepts any wording, not only "{subject} failed" (#319).
- An MdcScope never evaluates a value whose chain the code null-checks (`t.getTable() == null`, then `t.getTable().getUuid()`): no scope there, or the entry value is read null-safe at that step; in a body that is one `try` with catches the scope opens inside the `try` (an exception reading the value stays the code's own to catch).
- An id counts as in MDC only where a scope in the method covers the line: a line before the scope or in a catch outside it keeps its id.
- Lists of plain values (strings, numbers, enums, UUIDs, dates) and `.limit(n)` streams stay as logged; only collections of entities become `.size()`.
- A log-and-rethrow line that carries any value besides the exception is never deleted: an id in MDC here is not in MDC where the rethrown exception is logged.
- An added line never calls the argument expression again (no query re-run in a log argument); a write per loop item gets no line per item; any existing line counts, so a lowered line is not added again (#314).
- Generated wording: "Menu categories saved" (no "Entity" suffix, English plurals); a pipeline is named after its source (`openOrderIds`, not `toList`); a startup check gets no "completed" line and no "event handling" wording; `(order {})` for an id in MDC goes with it (#310).

**CLI**
- `support publish --repo …`, run in a service, records `support.repository` (and dependency mode) in its logast.yaml, so apply adds that repository with the starter; without one, the starter order says the build must resolve it (#295).
- A fix an earlier apply made and a person changed back is left as changed on the next apply (`Left as you changed them (N)`); `--redo` applies it again (#317).
- Apply lists the changes beyond logging (code that can run differently: scopes, moved evaluations, deleted or added statements, build and configuration files) as `file:line method — what`, and writes `.logast/reports/behavioural.txt` (#316).
- The test check no longer reports a static import from a library the main classpath lacks (Mockito, spring-test) as a break (#320).

## 0.5.2

**What logast writes**
- Ids read into locals one after the other get one MdcScope after the last declaration, not one nested scope per id (#311).
- A labelled value in parentheses (`not found (order {})`) goes with its noun (#310).
- MDC_KEY_NOT_IN_LOG_OUTPUT no longer adds keys that shadow the encoder's own fields (`timestamp`, `level`, `message`, …), and no longer trips its self-check when other fixes of the same round add keys (#296).

**CLI**
- The test check (#279) compiles only the tests of classes whose API changed (signatures, supertypes, annotations, instance fields): log lines and MDC scopes cannot break a test, and compiling every test that mentions a changed class took most of an apply on a large service.
- A Java package named `build` or `out` (`…common.build`) is source: parsed, copied into the scratch folder and written back (#313).
- Install without a checkout: `curl -fsSL https://raw.githubusercontent.com/magyar-attila-317/logast/main/install.sh | bash` downloads the latest release (`--version x.y.z` a given one), checks its sha256 and installs it; `logast update [version]` replaces an install the same way. Releases carry `logast-x.y.z.zip.sha256` and a version-less `logast.zip` (the latest).
- The compile check also sees constructors Lombok generates (`@RequiredArgsConstructor`) on classes a test or an edited file uses (#305).
- Apply rounds re-parse only the files that changed, against classes compiled from the same work tree; a round parses in full when that cannot be exact (a removed file, a changed API, many changed files). `LOGAST_PARSE=full` restores full parses; `LOGAST_TIMING=1` shows what each round did (#308).

## 0.5.1

**What logast writes**
- A key for a method whose body already is one MdcScope joins that scope's `MdcScope.of(…)` call instead of nesting a second scope (#311).
- Wording: a collection endpoint names what it creates (`POST /menu/categories` → "Category creation started"), a sub-resource's update or removal says so ("Item image deletion"), acronyms with digits stay ("E2E data seeder"), and "Starting X..." becomes "X started" without leftover dots (#307).
- A job that only hands its work to an executor gets no "completed" line; a started line goes after the job's leading `if (…) return;` guards; an event listener without a known rate gets no INFO outcome line; a class with several scheduled jobs names each by its method (#312).
- MDC keys the code writes are added to a JSON encoder's `includeMdcKeyName` list in logback-spring.xml / logback.xml, so ids moved into MDC reach the JSON logs (MDC_KEY_NOT_IN_LOG_OUTPUT, #296).
- A chain of labelled values (`thing {} order {}`) loses its nouns along with the values, instead of leaving "thing order" behind (#310).
- Generated job and listener lines keep acronyms ("NTAK reporting started"), say "… event handling" for a listener of an event that names what happened, and name the method when one class has several such entrypoints, so two never log the same line (#307).
- No started line in a job that runs more than `levels.addedInfoMaxPerDay` times a day (a 60 s poller), nor in an application event listener without a traffic.yaml rate (an AFTER_COMMIT listener runs as often as its publishers) (#306).
- An `Object[]` passed as SLF4J's varargs array is no longer rewritten to `.length` (#303).
- A line in a rethrowing catch that does not log the exception (a FAILED status line) is never deleted; a logged-and-rethrown line that carries other values is kept with a note (#304).
- The errors pack appends the cause right after the last argument, so `, e);` never starts a line (#301).
- `init` writes `entrypoints.trustInboundTrace` explicitly, so a changed default never changes a service's trust.
- `management.tracing.sampling.probability` is proposed, not written, unless `tracing.samplingProbability` in logast.yaml chooses it (#297).
- Callers' traces continue by default again (`entrypoints.trustInboundTrace: true`): the propagation lockdown (`consume: []`, `produce: [w3c]`, no baggage) is written only for a service that sets it to false (#270).
- An exception policy: `logging.exceptionStack: never` / `exceptionMessage: never` log the caught exception's class only (`[errorType=…]`) (#278).
- `logging.structuredProfiles: [prod, …]`: the JSON console format goes into those profiles' config only; dev, test and local keep plain logs (#278).
- Code that withholds an exception on purpose (a "deliberately" comment, a redacting helper) is reported, not changed (#290).
- AUDIT_RECORD_SECRET no longer reports an event's source, a Spring bean or collaborator fields (`passwordEncoder`); AUDIT_BEFORE_COMMIT no longer counts listeners routed by a SpEL `condition` as an early write (#282).
- The order adding a GitHub Packages repository says a token is needed even to read, and names workflows without `packages: read` (#277).
- Setup offers the service's own `@ResponseStatus(4xx)` exceptions as `levels.clientErrors` (#279).
- `messages.yaml` asks for at most `logging.wordingQueue` (50) texts at once, the most severe first (#292).

**CLI**
- The compile check runs Lombok on the classes an edited file uses: their getters no longer read as new errors (#305).
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
