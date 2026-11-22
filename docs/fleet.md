# Fleets

A fleet is a group of services that should log, trace and count the same way: the services of one team, one product
or one call chain. logast keeps a fleet in a folder on your machine. Services join it once, and from then on every
`scan` and `apply` of a member leaves a snapshot there. `logast fleet <name>` compares the members from those snapshots
without scanning anything again.

## The workflow

```bash
logast fleet create payments-fleet              # once per machine (or --dir <folder>, below)
cd services/checkout && logast fleet join payments-fleet
cd services/payments && logast fleet join payments-fleet
logast scan .                                   # in each member: scan, apply, ./gradlew logastApply / logastCheck
logast fleet payments-fleet                     # the overview
logast fleet payments-fleet --vocabulary        # the fleet's vocabulary worksheet and company.yaml
```

1. **Create** the fleet: `logast fleet create <name>` makes its folder (names: letters, digits, `.`, `-`, `_`) and
   its `company.yaml`, the policy every member extends, with every setting still commented out
   ([below](#the-fleets-companyyaml)).
2. **Join** in each service: `logast fleet join <name> [service root]` writes the fleet into the service's
   `logast.yaml` (creating the file if there is none; the rest of the file and its comments stay) and registers the
   service in the fleet folder:

   ```yaml
   fleet: payments-fleet
   extends:
     - fleet:payments-fleet    # = /home/me/.local/share/logast/fleets/payments-fleet/company.yaml on this machine
   ```

   `fleet:` makes the service a member; the `extends:` entry shows where its policy comes from. An existing `extends:`
   (a single entry or a list) gets the fleet entry first, the rest stays; joining again adds nothing. The name is the
   only thing the repository needs: it is committed, and each machine finds the fleet's folder itself (below). The
   comment says where that is on this machine; `scan` and `apply` refresh it when it changes.
   Join also **tidies** the service's own settings against the fleet's company.yaml: a setting with the same value
   as company.yaml is removed (with the comment on its line; a block such as `logging:` left empty goes too), and so are
   the vocabulary entries company.yaml defines in full (as `logast vocabulary --prune`). A setting with another value is
   the service's own override: it stays, and join lists it (`checkout keeps its own logging.keyStyle: snake (company:
   camel); …`); `--follow-company` removes those too. Join prints each line it removed; nothing else in the file
   changes. A logast.yaml with nothing but `fleet:` (and `extends:`) counts
   as not set up: the first `apply` writes its starter choices (packs, key style, log format, the vocabulary found in
   the code) below the fleet line. A setting the fleet's company.yaml (or an extended layer) already decides stays its
   decision: its line is written commented out, `# keyStyle: camel   # decided by fleet payments-fleet's company.yaml`.
3. **Scan or apply** as usual. Each run of `scan`, `apply` (not `--dry-run`), and the Gradle plugin's `logastApply`,
   `logastScan`, `logastCheck`, `logastBaseline` and `logastSarif` (they run the same commands) writes
   `<fleet>/services/<service>.json`: score, findings by severity and rule, the vocabulary keys with their evidence,
   outgoing calls and entrypoints, meters, commit and time. A service whose `logast.yaml` names a fleet that this
   machine has, but which is not on its member list yet (a colleague's clone), is added on its first scan. Writing the
   snapshot never fails the command: a problem is one warning line.
4. **Look at the overview**: `logast fleet <name>`.
5. **Decide the company's settings** in `company.yaml` (uncomment what the members agree on) and **the vocabulary** in
   the fleet's worksheet (below); every member takes them on its next run.

`logast fleet leave [service root]` takes a service out: the `fleet:` line, the `fleet:<name>` entry of `extends:` (and
an `extends:` left empty), its member entry and its snapshot go.
`logast fleet tidy <name> [--follow-company] [--service <member>]` tidies every joined member's `logast.yaml` as join
does (each repo's file only) and prints per member what it removed. Uncommitted changes of `logast.yaml` don't block it
(logast's own init, join and scan write that file, and apply's guard leaves it out too); the output says so, and
`git diff logast.yaml` shows them together with the tidy.
`logast fleet list` shows the fleets this machine knows, their folders and members.

## Adding repos without touching them

To see where a set of repos stands before anyone changes them, add them instead of joining:

```bash
logast fleet create shop
logast fleet add shop ~/src/orders ~/src/stock --scan      # shell globs work: ~/src/*/
logast fleet add shop --from repos.txt --scan              # one repo root per line, '#' starts a comment
logast fleet shop                                          # the overview, members marked added
logast fleet shop --service orders                         # what `logast scan` would print in orders
logast fleet shop --refresh                                # rescan every member
logast fleet remove shop orders                            # by service name or repo root
```

- **Nothing is written into an added repo**: no `logast.yaml`, no `.logast/`, no Gradle project cache. Its
  `git status` stays as it was. The members file lists it under `added:` (joined members stay under `members:`).
- **Its policy**: logast's defaults → the fleet's `company.yaml` → the repo's own `logast.yaml`, only if it already has
  one (its settings win, as for a joined member).
- **Its scan** (`add --scan`, `fleet <name> --refresh`) is the analysis of `logast scan`; every output goes to
  `<fleet>/services/<service>/`, laid out like `.logast/`: `classpath-*.txt`, `reports/orders.json`,
  `reports/coverage.txt`, `reports/metrics.md`, `reports/queries.md`, `reports/lifecycle.txt`. The snapshot is
  `<fleet>/services/<service>.json` as for a joined member. The classpath comes from the repo's own Gradle (the classpath
  task only, nothing compiled), run with `-Plogast.outputDir=<that folder>` and its project cache there too; or give
  one file for all with `--classpath-file`. When Gradle fails, the scan uses the classpath of the last scan, else none
  (one warning). A scan never applies anything, and writes no decision files (messages.yaml, metrics.yaml, traffic.yaml).
- **`--service <name>`** prints the findings of the member's last scan the way `logast scan` prints them, from the
  stored `orders.json` (an added member's in the fleet folder, a joined member's in its `.logast/reports/`), with where
  the reports are. The footer `logast scan` adds (decision files, next steps) is left out: it would write into the repo.
  With `--refresh` or `--kb` it picks the one member the run works on instead (#223): only its fetch, scan, rescan
  and knowledge base (`logast fleet . --kb-full --service cache-svc`); the dependencies phase still checks every
  member, so another repo's migrations that need its tables run again. A member whose migrations ran against other repos' tables
  says which and whose (`ran against other repos' tables: cache.products (cache-svc)`, #227). A member without Java (a Node.js, Helm or
  Terraform repo) is never scanned for findings: its facts come with the knowledge base.
- Everything else treats an added member like a joined one: trace links, metric names, vocabulary, the company.yaml
  suggestions.
- `logast fleet join` in an added repo makes it a joined member (it moves to `members:`); `fleet add` leaves a joined
  member as it is. `fleet remove` takes only added members (with their snapshot and results); a joined one uses `leave`.

## The overview

```text
fleet payments-fleet  ~/.local/share/logast/fleets/payments-fleet  (2 members)
  checkout  joined  score  21.6  open   4 (high 2, medium 1, low 1)  snapshot 3 min ago, 4f2a9c1
  payments  joined  score  42.1  open   5 (high 0, medium 2, low 3)  snapshot 2 h ago, 77b01de

Trace across services: 1 call reaches another service, 1 drops the trace
  checkout -> payments  POST /payments
    call   src/main/java/com/example/checkout/PaymentClient.java:16  RestClient, PaymentClient.pay
    entry  src/main/java/com/example/payments/PaymentController.java:15  POST /payments, PaymentController.pay
    breaks at the caller (checkout): RestClient in a field initializer: no traceparent header on outgoing calls
      fix: order HTC-f572b7 (HTTP_CLIENT_UNTRACED) in checkout: Build it in the constructor from an injected, …
    confirmed: host payments
One outcome, several metric names: 0 candidates (pick one name; nothing is merged)

Vocabulary: 1 conflict between keys nobody decided yet
  paymentId ~ payment_id: same type Long, source Payment.id(); differs: services checkout vs payments
  to decide: 2 (2 new): paymentId (new), payment_id (new)
4 settings all members agree on aren't company policy yet → ~/.local/share/logast/fleets/payments-fleet/company.yaml
```

- Per member: `joined` or `added` ([above](#adding-repos-without-touching-them)), score (as in the
  [fleet report](commands.md#fleet)), open findings, the snapshot's age and commit. A member without a snapshot says
  where to run `logast scan` (an added one: `--refresh`). `--json` has the same as `member: joined|added`.
- **Trace across services** and **one outcome, several metric names**: the same comparison as `logast fleet <paths…>`
  ([commands](commands.md#fleet)), made from the snapshots. When several members have the path an HTTP call goes to,
  the host of its base URL decides: a host that names a member (its member name, folder or `spring.application.name`;
  `billing.payments.svc.cluster.local` names `billing`) is **confirmed**; with no host known, one member is **path
  only** and several are one **ambiguous** row listing the candidates; a host that names another member or none is not
  a link but listed under **Same path, other host** (a stale URL?). An OpenFeign client without `url` takes its URL
  from `spring.cloud.openfeign.client.config.<name>.url` (or `feign.client.config…`) when that is set; otherwise its
  name is used, which names a member only when clients are named after services: a name like `reportingClient` that
  names none counts as no host, so the path decides (**path only**). Snapshots from before this keep working; their
  services have no `spring.application.name` until the next scan, and their Feign client names count as hosts.
- **Vocabulary**: keys nobody decided yet that look like one value under two names (same identity, source or type)
  or one name with different types, and what is left to decide.
- **Settings all members agree on** that `company.yaml` does not set yet (packs, log format, key style, support mode):
  the suggestions [in company.yaml](#the-fleets-companyyaml). No line when there are none. `--html` lists them too.
- **Own settings next to company.yaml**: per joined member, what `logast fleet tidy` would do, read from its
  `logast.yaml` without changing it: `orders: 2 settings company.yaml already sets (logging.structuredFormat, packs) ·
  1 own override (logging.keyStyle: snake) → logast fleet tidy shop`. No section when every member is tidy.

A member whose git `HEAD` moved since its snapshot is rescanned by every overview (#150), with one line saying so;
`--refresh` is only needed to rescan everything and to update the clones of `repos.yaml`.

Options: `--refresh` rescans every member first (from the roots in the member list; a member not at its root on this
machine keeps its snapshot; an added member's scan writes only to the fleet folder); `--service <name>` prints one
member's findings from its last scan; `--json` prints the overview as JSON.

**Next steps** (#149) end the overview and top the report page: what to do next, most basic first, each with the
exact command or file: members without a snapshot (`--refresh`), settings every member uses that company.yaml does not
set yet (uncomment them), settings the members disagree on (decide), members that repeat company settings
(`logast fleet tidy <name>`), vocabulary conflicts (the worksheet), calls that drop the trace (`logast apply .` in the
service that breaks it), high findings (`logast apply .`, lowest score first). The overview's `report:` line is a
`file://` URL (clickable in most terminals); `--open` opens the page in the browser. `--json` has the steps as `next`.

**The report page** `<fleet>/report/fleet.html` is rewritten by every `logast fleet <name>` and by every member's scan or
apply (#141), so it is never older than the newest snapshot (`--out <dir>` writes it elsewhere; `--html` is still
accepted). Its history keeps one `fleet-<date>.json` per day; the trend compares with the last earlier day. On the page:
rule ids come with their plain label, and hovering one shows what it finds and how it is fixed (from the rule tables of
the pack pages, #142); long tables scroll inside their box with the header kept in view (#143); clicking a service (its row,
or its box in the diagram) opens a drawer with its breakdown: pack scores, every rule with its fix, its calls and callers
with where the trace breaks, its own settings, meters and the command to fix it (#144).

The report page starts the trace section with a diagram of the links (#140): a box per service, callers left of their
callees, one arrow per caller → callee pair. The line shows how sure the match is (solid: confirmed by the host, or a
message; dashed: path only; dotted: ambiguous, one arrow per candidate), red when the trace breaks on at least one of
the pair's calls. Hovering an arrow lists its calls and how many break; clicking a service shows only its links. Calls to
another host are no links and are not drawn; services without links are listed under the diagram. Columns fan out over
the tallest one and are ordered so that arrows cross less.

## The fleet's vocabulary

`logast fleet <name> --vocabulary` writes the [vocabulary worksheet](vocabulary.md#one-vocabulary-across-services)
from the members' snapshots. Decide by uncommenting a `# decide:` line under a key, then run `logast fleet <name>`:
every run keeps the decisions, refreshes the evidence and regenerates the fleet's `company.yaml`:

| file in the fleet folder | holds | edited by |
|---|---|---|
| `vocabulary-worksheet.yaml` | every key of every member with its evidence, and your decisions (`term`, `same_as`, `local`, `owner`, `area`) | you decide; logast fills in the evidence |
| `company.yaml` | the fleet's policy ([below](#the-fleets-companyyaml)): the part between the `# >>> logast:` and `# <<< logast:` lines is **generated** from the decided terms (`vocabulary`, `areas`), the one between `# >>> logast suggestions` and `# <<< logast suggestions` from the snapshots; everything outside them (packs, logging, rules, …) is yours and kept | you, outside the marked parts |
| `members.yaml` | service → its root on this machine | `join` / `leave` |
| `services/<service>.json` | the members' snapshots | every scan and apply of a member |

A member's `logast.yaml` extends the fleet's `company.yaml` before its other layers (the file's own `extends:` and
settings come after it and win), whether it says so with `extends: [fleet:<name>]`, with `fleet: <name>` (a file
from before the `extends:` entry), or both: company.yaml is one layer either way. Nothing is copied into the service, so a
decision reaches every member on its next run: `scan` reports the new aliases and `apply` renames them
(MDC_KEY_ALIAS). A fleet this machine does not know is a policy warning; the service's own rules still apply.

## The fleet's company.yaml

`logast fleet create` writes `company.yaml` at once: a header, the settings a company usually decides (`packs`,
`logging.structuredFormat`, `logging.keyStyle`, `support.mode`, `support.repository`), each **commented out** with one
line on what it does, and the empty generated vocabulary part. Nothing in it is active until you uncomment it.

Every snapshot records the member's effective settings (all layers merged, defaults included). From them, logast keeps
a marked part of **commented suggestions** with counts, rewritten whenever a member's scan or apply writes its snapshot
and by `logast fleet <name>`. After two members scanned:

```yaml
# >>> logast suggestions: what the members' snapshots show, rewritten with every snapshot; a line you uncomment here is moved above this part and kept
# packs: [logging, tracing, metrics, resilience, errors, audit]   # 2 of 2 members use it: uncomment to make it the company's
# logging:
#   structuredFormat: logstash   # 2 of 2 members use it: uncomment to make it the company's
#   keyStyle: camel   # 1 of 2 (payments: snake): decide, then uncomment
# support:
#   mode: vendor   # 2 of 2 members use it: uncomment to make it the company's
# <<< logast suggestions: end
```

- **Suggestions, never active values.** company.yaml is a layer every member extends: a value logast set by itself
  would change other teams' services without anyone deciding it. A disagreement names the members that differ.
- **Uncomment to decide**, in the settings at the top (with its parent: `logging:` and `  keyStyle: camel`). A
  setting company.yaml sets gets no suggestion any more. A line uncommented inside the suggestions part is moved above
  it on the next refresh and kept. When `logging:` (or `support:`) is already set above, the suggestions say so on
  their own `# logging:` line: put the line under the existing one, a second `logging:` would be a duplicate key.
- logast never rewrites lines outside its marked parts. A company.yaml that does not parse is left as it is.
- A fleet created before this gets its company.yaml (or, in an existing one, the suggestions part before the
  vocabulary part) on the next snapshot.

## A fleet in its own git repo

`logast fleet init <name>` makes the current folder (a git repo, e.g. `my-company-logast`) the fleet's home (#147): the
fleet folder as `fleet create --dir .` makes it, plus

| file | what it is |
|---|---|
| `repos.yaml` | the members by repo URL (`https://…`, `git@…`; an entry may name a `branch`, a `name`, and the `rows` its migrations seed that become facts) |
| `README.md` | what the owner does, and what service developers run |
| `.github/workflows/logast-fleet.yml` | on push to main/master, weekday mornings and by hand: builds logast at the commit that scaffolded it (`LOGAST_REF`), clones the repos, runs `logast fleet . --refresh --kb --no-builds --classpath-file .cache/classpath.txt`, commits `kb/` when it changed, writes the overview to the run's summary and keeps `report/` as the run's artifact; publishes it to GitHub Pages only when the repository variable `LOGAST_PAGES` is `true` |
| `.gitignore` | `.cache/`, `services/`, `report/`, `members.yaml`: generated, never committed |
| `classpath/build.gradle.kts` | the libraries the workflow resolves the members' code against (common Spring ones; add your company's): `gradle -p classpath writeClasspath` writes `.cache/classpath.txt` |

Existing files are kept. `logast fleet <path> --refresh` (`.` in the fleet repo) clones each repo of `repos.yaml` shallow
(newest commit of its branch) into `.cache/repos/<name>`, or fetches and resets an existing clone, registers it as an
added member and scans it read-only. A folder with `repos.yaml` is a fleet even without `members.yaml` (a fresh checkout).

A repo whose Flyway migrations seed tables you want as facts (a scheduler's pipelines, a lookup table) names them in
its entry (#216); its export then runs its migrations (Docker) and writes those rows, without `--flyway` and for that
repo only. `--rows` on the command line adds tables for every member.

```yaml
repos:
  - url: https://github.com/org/scheduler.git
    rows: [scheduling.pipeline]
```

- **`--no-builds`**: an added member is scanned without running its own Gradle. In the fleet's CI a member's build
  script would run next to a token that reads every repo, so it never runs there; the code is resolved against
  `classpath/build.gradle.kts` instead (survey quality: types of the members' own libraries stay unresolved). Precise
  scans with each repo's own build come from the repo's own CI (#146).
- **Private repos** in the workflow: a repository secret `LOGAST_REPOS_TOKEN` with read access to them (a fine-grained
  token with *Contents: read*, or a GitHub App token); without it only public repos clone.
- **Who sees the page**: on GitHub Free, Pro and Team a Pages site of a private repo is public on the internet; only
  Enterprise Cloud restricts it. The report names services, endpoints, hosts and file paths, so publishing is off until
  `LOGAST_PAGES` is set; the artifact is always there.
- Service developers register a clone of the fleet repo (`logast fleet create <name> --dir <clone>`) and join it; the
  README says how. Extending the fleet by its git URL and listing a GitHub org: #146; rolling out: [below](#rolling-out-the-policy-236).

## Rolling out the policy (#236)

The fleet repo says what the policy is; each service repo says whether it takes part and which policy version it runs.
Every fact has one writer, so the two cannot drift apart:

| fact | written by | read by |
|---|---|---|
| company policy (company.yaml) | the fleet repo, as **tagged versions** `policy-N` | members, by tag |
| which version a service runs | the service repo: `extends: [fleet:<name>@policy-N]` in its logast.yaml, changed only together with the code it produced | the fleet |
| taking part, holds | the service repo: `rollout:` in its logast.yaml | the rollout |
| PR open / merged | the forge | the fleet |

**Policy versions.** `logast fleet tag-policy [fleet]` tags the fleet repo's HEAD `policy-<n+1>` when company.yaml
changed since the newest tag (uncommitted changes are refused); push the tag. A member pinned to a version
(`- fleet:<name>@policy-3`) applies that version's company.yaml, read from git at the tag, on every machine and in CI,
whatever its clone of the fleet repo holds; an unpinned `fleet:<name>` still follows the fleet folder.

**The rollout from your machine.** In the fleet repo, with your own git and gh logins. Every stage is asked for:

```bash
logast fleet tag-policy .                       # company.yaml changed: policy-4
logast fleet rollout .                          # the plan: per repo due or not, and why; changes nothing
logast fleet rollout . --apply                  # a preview per repo in .cache/rollout/<repo>: review it there
logast fleet rollout . --commit                 # commits exactly the preview on logast/rollout (local)
logast fleet rollout . --repos checkout --pr    # pushes that commit and opens (or updates) the pull request
```

For each repo of repos.yaml (cloned or fetched as `--refresh` does):
1. **Due?** decided by the repo's own logast.yaml on its default branch: `rollout: { mode: off }` (opted out) and a
   `hold` are skipped, `mode: manual` too unless `--repos` names it; a repo pinned to the target version (or newer)
   is up to date.
2. **Its branch.** `logast/rollout`, regenerated from the default branch (never rebased: apply is deterministic).
   A branch whose newest commit lacks the `Logast-Rollout:` trailer has someone's commits on top: it is theirs, left
   alone.
3. **`--apply`: the preview.** In a work tree: the pin (joining the fleet when the repo has not), `logast apply`, the
   repo's `./gradlew build` (`--no-build` skips it). A failing apply or build is **blocked**, with its log in
   `.cache/rollout/<repo>.log`. The result's git tree id is recorded with what made it (base commit, policy version,
   logast version).
4. **`--commit`** commits exactly that tree (`git commit-tree`), with trailers `Logast-Rollout: policy-N`,
   `Logast-Base: <commit>`, `Logast-Version`. A preview whose files changed after apply is discarded, never committed:
   logast commits only what apply produced; changes of your own go on the pull request, as a commit anyone can see.
5. **`--push`** pushes that commit with a lease (a branch someone moved meanwhile is not overwritten); **`--pr`** opens
   or updates the pull request (`gh`).

A later stage reuses the earlier one's result while the base commit, the policy version and logast are the same
(`--commit` after `--apply` does not apply again; `--pr` pushes the commit `--commit` made); when one of them moved,
the stage makes it again and says so; `--fresh` always does. Each stage includes the ones before it when there is
nothing to reuse.

```yaml
# a service's logast.yaml
rollout:
  mode: manual                        # pr (default) | manual | off
  hold: "until 2026-12-15: freeze"    # skipped until that day; without a date until removed
```

A second run on an unchanged repo and policy does nothing. The fleet's own CI never rolls out: its token reads every
repo and member builds never run next to it. Rollout states in the overview, a workflow per service repo and waves
follow (#236).

## Which logast made it

A member's snapshot (`services/<member>.json`, its findings) records the logast build that made it (#195): a plain
`logast fleet <name>` rescans a member with new commits, and also one whose snapshot another logast made, so the
overview never shows findings of older rules. The knowledge base's facts carry the build in their header,
`kb/<member>/.export.json` too (a new build exports again), and `logast mcp`'s `summary` lists which builds wrote the
loaded facts. A running `logast mcp` notices when logast was installed again under it and says in every answer to
restart the MCP client: until then it answers with the old code.

## What a run shows

`--refresh` and `--kb` runs say what they will do (the phases: update, scan, knowledge base, report), then per phase
one line per repo as it finishes: `[3/32] ✓ name  46s  what happened`, with the time left once it is worth saying,
problems indented below it, and on a terminal a live line with the repo being worked on and its step (#193). A
summary closes the run: per phase how many repos were ok, unchanged, with problems, failed or skipped, how long it
took and the slowest repos, and what to run next. The overview itself still goes to stdout.

## The fleet's knowledge base (`kb/`)

The fleet repo is also where the **knowledge base** lives: every member's facts (endpoints, jobs and their schedules,
tables and who touches them, topics, deployments, secrets), which `logast mcp` serves to any LLM client, so questions
about the whole system (*who writes this table, what runs Monday in prod, what stops if this service is down*) come back
with the file and line behind every fact. What it answers and how to connect a client: [mcp.md](mcp.md); what the facts
are: [facts.md](facts.md). This section is how the fleet builds it.

`logast fleet <name|path> --kb` (with `--refresh`, after updating the clones) exports every member, whatever its
layout, into `<fleet>/kb/<member>/` (as `logast export --out-dir`, [facts.md](facts.md#a-whole-repo-any-layout---out-dir)),
then applies each mapping in `kb/mappings/*.y(a)ml` into `kb/mapped/` (#182). `logast mcp config --kb <fleet>/kb`
connects an MCP client to all of it ([mcp.md](mcp.md)).

| in `kb/` | what | who writes it |
|---|---|---|
| `<member>/*.jsonl` | each member's facts, one file per root | `--kb` (replaced every run) |
| `mapped/*.jsonl` | what the mappings make of them | `--kb` |
| `knowledge.yml` | glossary, abbreviations, other names of services, decisions | you (`fleet init` writes a commented one) |
| `mappings/*.yaml` | company-specific facts (a scheduler's job rows as jobs, …) | you |
| `knowledge.proposed.yml`, `knowledge.decisions.yml` | the LLM's proposals, the review page's decisions | `logast mcp`, `logast mcp review` |

A member is exported again only when its commit or the options changed since its last export (`kb/<member>/.export.json`
records both, with logast's build: a new logast re-exports every member once); `--kb-full` exports every member with everything (it implies `--kb` and `--flyway`; a member without migrations
needs no Docker). An export with problems (Terragrunt or Flyway that could not run, a file
logast could not read) is not recorded, so the next run exports it again once the cause is fixed. With `--refresh`, a member is exported right
after its scan, with that scan's classpath and analysis; the others go `--kb-threads` at a time (default 2: one
analysis can take a few GB). `--threads n` does the same for `--refresh`'s scan: n repos scanned (and each exported
with its own analysis) at a time, one live line for the repos in progress (#226); raise either as memory allows. `--flyway` (with `--rows`, `--postgres-image`) adds the tables each member's migrations
create, per environment, as `logast export --flyway` (needs Docker); changing it re-exports every member once (#184).
A migration needing a table another member creates runs against a stand-in with that table's columns (#205,
docs/facts.md); after the knowledge base phase, a **dependencies** phase runs the members whose needs resolve
differently now (a member exported later makes it, or its columns changed) again, round by round for chains. A member
with migrations left out (nothing in the fleet makes what they need) shows `!` with the migration and what is missing;
its stamp keeps those needs, so it is exported again once some member makes them.

In a fleet repo, the workflow `fleet init` writes runs with `--kb` and commits `kb/` when it changed (with the job's own
token), so `git pull` in a clone brings everyone the newest knowledge base. The facts name endpoints, tables, hosts and
file paths: the fleet repo is as private as the repos it reads.

## Where fleets live

The fleets folder, first match wins:

1. `--fleets-dir <dir>` on the `logast fleet …` command (for that run);
2. the `LOGAST_FLEETS` environment variable;
3. `fleets.dir` in the user config, `~/.config/logast/config.yaml` (`$XDG_CONFIG_HOME/logast/config.yaml` when set);
4. `~/.local/share/logast/fleets/` (`$XDG_DATA_HOME/logast/fleets/` when set).

A single fleet can live elsewhere, a synced or git-tracked folder for example: `logast fleet create <name> --dir
<folder>`. The user config remembers it (`fleets.paths.<name>`), and that wins over the fleets folder for that fleet.
`create` with `--fleets-dir` remembers the folder the same way, since a later scan would not look there.

```yaml
# ~/.config/logast/config.yaml (logast writes the fleets.paths entries; comments in this file are not kept)
fleets:
  dir: ~/work/fleets
  paths:
    payments-fleet: /home/me/git/payments-fleet
```

## The earlier commands

They keep working:

- `logast fleet <service roots…>` scans the given roots and writes the report (`--out`, default `logast-fleet/`);
- `logast fleet --vocabulary [--central <dir>] <roots…>` lists per service what apply would rename;
- `logast vocabulary --central <dir> <roots…>` and `--prune` work on a folder of your choice and `extends:` by path.

A named fleet replaces all three: no list of paths on each run, and no folder to remember.
