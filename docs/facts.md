# Facts

A **fact** is a small, citable statement about code or deployment: *endpoint `GET /shipments/{id}` exists in
shipping, `ShipmentController.java:23`*; *this endpoint writes table `shipping.shipment`*. `logast export` writes the
facts its analysis finds; other extractors (Helm, Terraform, CI, or private ones) write the same format, so a tool
that loads them can join facts from many repos without knowing who produced them (#152). `logast mcp` serves them to
an LLM client ([mcp.md](mcp.md)).

## The file

JSON Lines, UTF-8: a header line first, then one node or one edge per line. Empty lines are ignored.

```json
{"header":{"schema":"kb-facts/0","extractor":"java","extractorVersion":"0.1.0","repo":"shipping","commit":"abc1234"}}
{"node":{"id":"service:shipping","kind":"service","attrs":{"application":"shipping"},"evidence":{"file":"src/main/resources/application.yml","line":1}}}
{"node":{"id":"endpoint:shipping:GET /shipments/{id}","kind":"endpoint","attrs":{"method":"GET","path":"/shipments/{id}"},"evidence":{"file":"src/main/java/com/example/ShipmentController.java","line":23}}}
{"edge":{"from":"service:shipping","kind":"has","to":"endpoint:shipping:GET /shipments/{id}","provenance":"extracted","evidence":{"file":"src/main/java/com/example/ShipmentController.java","line":23}}}
{"edge":{"from":"endpoint:shipping:GET /shipments/{id}","kind":"reads","to":"table:shipping.shipment","env":"prod","provenance":"extracted","evidence":{"file":"src/main/java/com/example/ShipmentService.java","line":41}}}
```

| line | fields (required in bold) |
|---|---|
| `header` | **`schema`** (`kb-facts/0`), **`extractor`** (its id), `extractorVersion`, **`repo`**, `commit`, `generatedAt` (ISO-8601; left out where output must be reproducible) |
| `node` | **`id`**, **`kind`**, `env`, `attrs`, **`evidence`** |
| `edge` | **`from`**, **`kind`**, **`to`**, `env`, `attrs`, **`provenance`**, `confidence`, `via`, `sources`, `evidence` |
| `evidence` | `repo`, `root`, **`file`**, `line`, `commit` |

## The rules

- **Ids** are `<kind>:<name>`: the kind in lower case letters, digits and dashes, the name any text without control
  characters (`endpoint:shipping:GET /shipments/{id}`, `table:billing.invoice`). A node's id starts with its kind. Ids
  are stable across runs: an edge, an approval or another extractor refers to a node by its id.
- **`env`**: left out when the fact holds in every environment; else the environment (`dev`, `prod`).
- **`attrs`**: any JSON object. Writers sort its keys, so the same facts give the same bytes.
- **Evidence** names the file (relative to the repo) and, where it has one, the line. `repo` and `commit` default to
  the header's; `root` is the folder an extractor worked on (a Gradle project, a chart, a Terraform unit).
- **Provenance** says how an edge is known:

  | provenance | meaning | needs |
  |---|---|---|
  | `extracted` | the files prove it | `evidence`; no `confidence`, `via` or `sources` |
  | `inferred` | a join or a company rule made it | `confidence` (above 0, at most 1) and `via` (the rule or entry) |
  | `llm` | an interpretation | `sources` (the facts, glossary entries and pages it rests on); no `via` |

- **Unknown fields are errors**, so a typo does not silently drop a value. Kinds are open: any kind that matches the
  pattern is valid, an extension adds its own.
- **Versioning**: additions keep `kb-facts/0`; a rename or a changed meaning makes `kb-facts/1`.

## Core kinds

Extractors share these, so joins and tools can rely on them; their names are built as shown.

| node kind | name | example |
|---|---|---|
| `service` | the service | `service:shipping` |
| `library` | group:artifact | `library:org.springframework.kafka:spring-kafka` |
| `endpoint` | service:METHOD path | `endpoint:shipping:GET /shipments/{id}` |
| `listener` | service:transport destination | `listener:billing:kafka orders.placed` |
| `job` | service:name | `job:shipping:nightly-export` |
| `call` | service:transport target | `call:orders:http GET /shipments/{id}` |
| `bean` | service:class | `bean:shipping:com.example.ExportJobExecutor` |
| `profile` | service:profile | `profile:shipping:nightly-export` |
| `property` | service:key | `property:orders:shipping.url` |
| `table`, `column`, `row` | schema.table(.column), table:key | `table:billing.invoice` |
| `topic` | name | `topic:orders.placed` |
| `file-location` | store:path or pattern | `file-location:sftp:/outbound/invoices/*.csv` |
| `secret` | vault/name | `secret:kv-payments/db-password` |
| `deployment`, `image`, `chart`, `workflow`, `resource`, `term` | as the extractor documents | |

Edge kinds: `has`, `calls`, `reads`, `writes`, `publishes`, `consumes`, `runs`, `after`, `active-with`,
`uses-property`, `uses-secret`, `depends-on`, `deploys`, `maps`, `alias`, `defines`.

## Schemas and roles made outside Flyway

Migrations that expect a schema or a role made elsewhere (a DBA, an init script, another service: `create table
cache.x`, `grant … to reporting_reader`) fail in the throwaway database. logast creates the missing one, empty, and runs
the migrations again (#192: a failed migration rolls back, PostgreSQL's DDL is transactional), and says so on stderr.
A migration that needs what another service makes (`relation|sequence|type|view … does not exist`: a view on its
table, a foreign key to it, its sequence) runs against a **stand-in** where it can (#205):
- a table another repo's migrations create (in a fleet: from the other members' facts, `withElsewhere` in code; a
  name without a schema only in the run's own schema) is created empty with that table's columns (and each column unique, for a foreign key to it); a sequence is created empty;
- the migration then runs in full; the stand-in is not the service's table: an edge `service:<svc> -reads->
  table:<schema.name>` with `via: migration <file>`, `madeElsewhere: true`, `standIn: true` and `columnsAssumed`.

Where nothing can stand in (no repo makes it; a type or a view of another service), the migration is **left out** and
the ones after it still run (#196). That is a fact too: the same `reads` edge with `leftOut: true` and `lost` (the
tables the left-out migration would have created); a migration that failed against a stand-in gets a `leftOut` edge
to `migration:<svc>:<file>` with its `error`. The MCP says the schema is incomplete: `summary` lists those services,
`describe` of the service says which migration and what is missing with it, and a question about a lost table says
why it is not in the facts.

## A whole repo, any layout: `--out-dir`

`logast export <repo> --out-dir <dir>` exports everything in a repo, without describing its layout (#182): every
Gradle build (a `settings.gradle(.kts)`, or a build file or `src/main/java` outside any other build; a build's modules
belong to it), every folder with a `helmfile.yaml`, the Terragrunt units wherever they are, and the repo's workflows and
Ansible inventory. `.git`, build output, `node_modules`, `.terragrunt-cache` and git worktrees inside the repo are
skipped. One facts file per root (`apps_orders.jsonl`, `repo.terraform.jsonl`, `repo.workflows.jsonl`); every fact's
evidence carries its root, so a source reads `repo/apps/orders/src/…:line`. A Java root's classpath: `--classpath-file`,
else the one its `.logast` has, else (unless `--no-builds`) its Gradle runs once, its output cached in
`<dir>/.classpath/` (the repo stays as it was). Files an earlier export wrote in `<dir>` are replaced.

## What `logast export` writes

`logast export <service> [--out facts.jsonl] [--repo <name>]` scans the service (read-only, as `scan` does) and writes
(#153):

| fact | id | attrs / edges |
|---|---|---|
| the service | `service:<spring.application.name, else the folder>` | `application`, `package`, `springBoot`; `has` each entrypoint, `depends-on` each library |
| an HTTP endpoint | `endpoint:<svc>:<METHOD> <path>` | `method`, `path`, `handler`, `observed` |
| a listener | `listener:<svc>:<kafka\|rabbit\|jms> <destination>`, other kinds `listener:<svc>:<kind> <label>` | `destination`, `handler`; `consumes` `topic:<name>` (Kafka) or `queue:<name>` |
| a job | `job:<svc>:<Class.method>` (scheduled, runners, batch steps), `job:<svc>:<class>` (Quartz; `#<name>` per JobDetail when a class has several) | `cron` / `fixedRate` / `fixedDelay` / `zone` as written (placeholders kept: each environment resolves them) |
| an outgoing call | `call:<svc>:<transport> <METHOD> <base><path>` or `call:<svc>:<transport> <topic>` | `client`, `base`, `target`; `calls` from each entrypoint that reaches it (or from the service, `reached: false`), `traceBreaks` when the trace stops there; `publishes` the topic or queue |
| a bean with a `@Profile`, `@Value` or `@ConfigurationProperties` | `bean:<svc>:<class>` | `profiles`; `active-with` `profile:<svc>:<name>` (`negated` for `!name`); `runs` from each entrypoint that reaches it; `uses-property` `property:<svc>:<key>` (`default`), `<prefix>.*` for configuration properties |
| a library | `library:<group>:<artifact>` | `version` (from the classpath's jar paths) |
| the repo (#162) | `repo:<name>` (`--repo`, else the folder) | `has` its service; `defines` each library it publishes |
| a library the repo publishes | `library:<group>:<artifact>` | `version`, `module`: every Gradle project (the root and `settings.gradle(.kts)` includes) that applies `maven-publish` or `java-library` and has a group (its build file, else `gradle.properties`); the artifact is the project's name unless `artifactId` / `archivesName` says otherwise. Services' `depends-on` edges (with the version they use) reach the same node, so `describe library:<g>:<a>` shows who owns it and who uses which version |
| an entity | `entity:<svc>:<class>` | `name` (for JPQL), `table`; `maps` `table:<schema.>name>` (`@Table`, else Spring Boot's default naming: `OrderLine` → `order_line`) |
| a table read or written (#154) | `table:<schema>.<name>`, or `table:<name>` when the code names no schema | `reads` / `writes` from each entrypoint that reaches the access (or from the service, `reached: false`), `via` (`InvoiceRepository.save`, `JdbcTemplate.update`); `parsed: false` when the SQL was read from its keywords |
| a file written or read (#172) | `file-location:blob:<container>/<blob>`, `file-location:sftp:<path>` | `writes` / `reads` from each entrypoint that reaches the call, `via` (`BlobClient.upload`, `ChannelSftp.put`): Azure Blob (`getBlobContainerClient`, `containerName`, `getBlobClient`; `upload…`/`delete…` write, `download…`/`exists`/`listBlobs` read), JSch's `ChannelSftp` and sshj's `SFTPClient` (`put` and `rm` write, `get` and `ls` read), Spring Integration's `Session` and `RemoteFileTemplate`. The location from constants, `@Value` (the base config's value, else `${key}`) and partly constant concatenations (`"/outbound/" + day` → `/outbound/{?}`) |
| SQL built at runtime | `sql:<svc>:<file>:<line>` | `uses-sql` from the entrypoints that reach it: where it runs is known, not which table |

Tables come from Spring Data repositories (CRUD and derived methods by name: `save…`, `delete…` write, the rest
read; `@Query` native SQL and JPQL, `@Modifying` writes), `EntityManager`, and `JdbcTemplate`,
`NamedParameterJdbcTemplate` and `JdbcClient` with SQL the code states as constants (literals, text blocks, constants,
concatenations of them), parsed with JSqlParser. Unquoted names are lower case, as PostgreSQL folds them; a schema in
the SQL or in `@Table` is kept, so cross-schema reads show as such. Not yet: a naming strategy other than Spring Boot's
default, stored procedures (named only by their call).

Facts are sorted and repeats dropped, so the same code gives the same file.

### Tables per environment: `--flyway`

`logast export <service> --flyway [--rows <table>,…] [--postgres-image <image>]` also runs the service's Flyway
migrations in a throwaway PostgreSQL (Testcontainers: needs Docker) and writes what they create (#157):

- **Environments**: a migration folder (`spring.flyway.locations`, default `db/migration`) with a `common` subfolder
  and others (`dev`, `ppe`, `prod`, …) is run once per other subfolder, as `common` + its own; otherwise once, for all.
  A profile whose config sets its own `spring.flyway.locations` (`application-prod.yml`: `common` and `prod`) is an
  environment of its own, run with those locations, besides the base config's locations run once (#180).
- **Placeholders** (`${schema}`): `spring.flyway.placeholders.*` of the environment's profile
  (`application-<env>.yml`) over the base config. A placeholder with no value runs with its own name; a placeholder
  used as a schema (`${schema}.jobs`) gets that schema created first.
- **Facts**: `table:<schema>.<name>` per environment (`env`), with its `columns`, the migration's `create table` line
  as evidence; each row as `row:<schema>.<table>:<primary key>` (else `#<position>`), its columns as attributes, of
  the tables the migrations fill with a few rows (up to 50: reference data, a scheduler's definitions; #215) and of
  the tables asked for with `--rows` (or `rows:` in a fleet's `repos.yaml`), whatever their size.
- **Dropped tables** (#227): the tables are read after each migration too, so one a later migration drops is still a
  `table` with `dropped: true`, the columns it had last and `droppedIn` (the script that dropped it): it existed while
  the migrations between ran, and another repo's migrations of that time that need it run against it.
- **Migrations from libraries** (#181): a `classpath:` location is looked up on the service's classpath too, as Flyway
  scans it at runtime: class folders, and jars by their entries under that path, so a library that ships migrations
  in its jar runs with the service's own; its tables cite `<jar>!/<entry>`.
- **Migrations the code runs itself** (#179): a `Flyway.configure()…locations(…)` chain in the service's code with a
  constant location Spring Boot does not run (a multi-tenant service migrating each tenant's schema) is run too, into
  its constant schema, or, when the code picks the schema at runtime, into a stand-in named after the location's last
  folder (`tenant`); those tables say `migratedBy` (the code) and `perTenant: true`. The code's schema-less table
  names join them through `same-as`.
- An environment whose migrations fail is named on stderr, with the placeholders that had no value; the others are
  still written.

## A Node.js repo: its TypeORM migrations

A folder whose `package.json` depends on TypeORM is a Node.js service (#222): `service:<package name>` (scope dropped,
`runtime: node`). With `--flyway` (a fleet's `--flyway`, or `rows:` for it in `repos.yaml`), its migrations run in
PostgreSQL like a Java service's: each class implementing `MigrationInterface`, in TypeORM's order (the timestamp in
its name), runs the SQL its `up()` passes to `queryRunner.query(…)` (string or template literals; `down()` never
runs). Its tables are `table:<schema>.<name>` with their `columns`, citing the migration's `.ts` file and the line of
the statement, and seeded rows and the schemas it expects from outside work as for Flyway. Migrations that build the
schema through TypeORM's API (`queryRunner.createTable(…)`) or SQL built elsewhere are not read, nor are entities
synchronised without migrations. Another repo's migrations that need its tables run against them (#205).

## A Helm repo: what runs where

`logast export <repo>` on a repo with a `helmfile.yaml` (or `.gotmpl`) writes what runs in each environment (#160):

```bash
logast export ~/src/deploy --out kb/deploy.jsonl                       # runs helmfile list + template per environment
logast export ~/src/deploy --env dev,prod --helm-render kafka-topics    # also render a third-party release
logast export ~/src/deploy --rendered out/                              # out/<env>.yaml rendered elsewhere: no CLI needed
```

- **Environments**: `--env`, else the helmfile's `environments:`.
- **Rendered**: the releases whose chart is in the repo, plus `--helm-render` ones (third-party charts that carry your
  topics, users or dashboards). Every release, rendered or not, is a `release:<name>` fact with its chart, version and
  namespace. `helm` and `helmfile` must be on `PATH` (or use `--rendered`).
- **Namespaces**: `helm template` leaves them off the objects; each object gets its release's namespace from
  `helmfile list`.
- **Facts** per environment: `deployment:<name>` (Deployment, StatefulSet, DaemonSet, Job) with `images`, `env` (a
  value, `configmap:<name>/<key>`, or `secret:<vault>/<object>` for a Key Vault object a SecretProviderClass syncs into
  a Secret), `envFrom`, `replicas`, `release`, `chart`; `deploys` `image:<repository>` (`tag`); `uses-secret`
  `secret:<vault>/<object>`. CronJobs are `job:<release>:<name>` with `schedule`, `scheduleFormat: unix` and `zone`, so
  `runs_on` lists them. `k8s-service:<name>` `routes` the workloads it selects, `ingress:<name>` (`hosts`) `routes`
  services. Strimzi `topic:<name>` (`partitions`, `replicas`, `config`; evidence: the values file line that names it)
  and `kafka-user:<name>` `may-read` / `may-write` topics. Grafana dashboards (ConfigMaps with `grafana_dashboard`):
  `dashboard:<title>` `queries` `metric:<name>` for the metric names in their queries.
- **Evidence**: the chart template from the manifest's `# Source:` line, as a path in the repo (subcharts of an
  umbrella chart included).

## A Terraform / Terragrunt repo: what exists underneath

`logast export <repo>` on a repo with Terragrunt units (and no Java build or helmfile) writes its resources per
environment, without the `terragrunt` or `terraform` CLIs: no state, no credentials (#166).

- **Units**: every `terragrunt.hcl` with a `terraform` block. Its `inputs`, over those of the file it includes
  (`find_in_parent_folders("root.hcl")`), and the module's variable defaults evaluate the module (HCL parsed with
  hcl4j), so names built from variables and locals come out as the environment has them. Values Terraform only knows
  at plan time (functions such as `lookup`, other resources' attributes) stay unknown and are left out.
- **With `terragrunt` on the PATH** (#175), a unit's inputs and source come from `terragrunt render --format json`
  (1.x), or `render-json` into a temporary file on older versions (checked with 0.67), so Terragrunt's own functions
  (`get_env`, `get_terragrunt_dir`, `read_terragrunt_config`) are evaluated; nothing is written into the repo. If both
  fail, the HCL is read as above. The unit says which (`inputsFrom: terragrunt|hcl`). `LOGAST_TERRAGRUNT` picks another
  terragrunt binary than the PATH's, `LOGAST_TERRAGRUNT=off` reads the HCL only. Units render 4 at a time
  (`LOGAST_TERRAGRUNT_THREADS`), with a progress line each; a render may take 60 s (`LOGAST_TERRAGRUNT_TIMEOUT`, in seconds:
  a `dependency` block reading remote state can hang), then the unit is read from its HCL, and after 3 such timeouts
  terragrunt is not run for the rest (#185).
- **Layout**: a unit is a leaf `terragrunt.hcl` (none below it) that, or the file it includes, names a `source`, so
  the classic root `terragrunt.hcl` (remote state, shared arguments) is no unit. The environment is a folder named like
  one (`dev`, `rtl`, `ppe`, `uat`, `staging`, `prod`, …) anywhere in the path, else, under
  `terragrunt/<subscription>/<region>/<env>/…`, the segment after the region. The module is the folder the unit's `source` names: relative, absolute, under `get_parent_terragrunt_dir()` or
  `get_repo_root()`, or `terraform/<module>`; a module in another repo (`git::`, a registry) is named, not read; one level of
  local submodules (`module` blocks with a `./` source) is followed with the block's arguments as variables.
- **Facts** per environment: `resource:<type>:<name>` for every resource (`location`, `sku_name`, `tags`, …); a Key
  Vault secret is `secret:<vault>/<name>`, the id the Helm facts use, with `content_type`, `expiration_date`, `tags`,
  and its vault `has` it; `tf-unit:<path>` `defines` them. Evidence: the module file and the line of the block.

## GitHub Actions workflows

Every `logast export` also reads the repo's `.github/workflows/*.yml` (a repo with nothing else, a CI repo, gets only
these) (#167):

- `workflow:<owner/repo>/.github/workflows/<file>` with its `triggers`, `runsOn` labels, `schedule` (a `schedule:`
  trigger, as `runs_on` reads it); a reusable one (`on: workflow_call`) has `reusable`, `inputs`, `secrets`.
  `<owner/repo>` comes from the `origin` remote (as other repos' `uses:` lines name it), else `--repo`.
- `calls` the reusable workflows a job uses, with the `ref`; `uses` the actions its steps use (`action:<owner/name>`,
  `ref`); `runs` the repo's scripts a step runs (`script:<owner/repo>/<path>`: `./scripts/x.sh`, `bash ci/y.sh`, files
  the repo has).
- What a caller passes (#214): a `calls` edge has the job's `with:` values as written (`${{ github.sha }}` kept); a
  `runs` edge has the script's `args` and the `env` the step sees (workflow, job and step `env:` merged). Secrets are
  never values (`<redacted…>`).
- Dispatches (#214): `gh workflow run <file> -R owner/repo -f k=v` and the REST API's
  `repos/<o>/<r>/actions/workflows/<file>/dispatches` (`inputs`) `dispatches` that workflow; `repos/<o>/<r>/dispatches`
  with an `event_type`, `gh api … -f event_type=…`, and a `…/repository-dispatch` action (`event-type`,
  `client-payload` as `payload`) `dispatches` `event:github:<o>/<r>:<type>`, which `triggers` the workflows with
  `on: repository_dispatch: types: [<type>]`. `workflow_dispatch` inputs are `dispatchInputs`.
- Well-known commands in a step's `run:` and in the repo's scripts it runs (#214), from the workflow or the
  `script:` node: `git clone <url>` `clones` `repo:<owner/name>` (a token in the URL is dropped), `git push` `pushes`
  it (the repo cloned, or the one `cd`'d into); `yq -i '<path> = …' <file>` and `sed -i '<expr>' <file>` `writes`
  `file:<owner/name>/<path>` (in the cloned repo when the path or the current folder is in it) with `via`,
  `expression` and, for `yq`, the `key` it sets; `helm upgrade --install <release> <chart>` `installs`
  `helm-release:<release>` (`chart`, `namespace`, `set`, `values`); `kubectl apply -f <file>` `applies` the file.
  A script's own assignments are used (`SERVICE="$1"` makes `.services.$1.image.tag`); other variables stay as
  written (`file:org/k8s/$VALUES`); other commands are not read.
- The chain resolved (#214): the knowledge base follows a script's `$1`/`$NAME` to the `args`/`env` of the step that
  runs it, then `${{ inputs.x }}` / `${{ github.event.client_payload.x }}` to each caller of the workflow (a call's
  `with:`, a dispatch's inputs or payload). The caller gets the effect, inferred (`via: workflow-chain`, `through` the
  workflow and script): `workflow:org/orders/…/release.yml -writes-> file:org/k8s/charts/services/values-prod.yaml
  {key: .services.orders-api.image.tag}`. So `neighbors` of a values file shows which service's release writes it.
- `logast mcp`'s `workflow_refs` lists, per reusable workflow, the refs its callers use.

## Self-hosted runners (Ansible)

A repo with an Ansible inventory (INI or YAML, under `inventory/` or named `hosts`/`inventory`) also gets its runner
hosts (#173): every host whose variables (inline, `group_vars`, `host_vars`, through parent groups) set runner labels
(`runner_labels`, `github_runner_labels`, …; a list or a comma string) is `runner-host:<host>`, with its `groups`,
`labels` and `count` (`runner_count`, `runners_per_host`). When facts load, a workflow on `self-hosted` labels
`runs-on` the hosts whose labels cover them (GitHub's default labels, OS and architecture, need no match); `logast mcp`'s
`runners` lists hosts, their workflows, and self-hosted workflows no runner fits.

## Company-specific facts: `logast facts map`

Some knowledge is your own team's: a scheduler that keeps its pipelines in database rows, a naming convention, which
team owns what. It does not belong in logast's code: logast discovers what code and configuration state, generically,
and your mappings join those facts the way only your team knows (#158, #215). A mapping file lives in your fleet's
`kb/mappings/` (committed in your private fleet repo, never in logast's); `logast fleet … --kb` applies every one into
`kb/mapped/`, and `logast mcp` loads the result with the rest.

### How a rule works

```
match   → which facts to start from      (kind, idPrefix, where)
each    → optional: once per item of a JSON list in a value   ({step.field}, {prev.field})
lookup  → optional: other facts, by id or the end of an id    ({as.attribute}, {as._id})
nodes / edges → what to write, as templates                   ({name | filter …})
```

An example: a scheduler's `pipeline` rows (made facts by `rows:` in `repos.yaml`, or `--rows`) list in a JSON column
the Kubernetes jobs they start one after the other; each of those is a rendered CronJob (`job:<release>:<name>`) that
starts a service's job (#211):

```yaml
mappings:
  - name: pipelines
    match: {kind: row, idPrefix: "row:agenda.routine:", where: {run_mode: CRON}}
    nodes:
      - {id: "job:agenda:{name}", kind: job, attrs: {schedule: "{run_spec}", scheduleFormat: spring, entrypoint: pipeline}}
  - name: pipeline-steps
    match: {kind: row, idPrefix: "row:agenda.routine:"}
    each: {attr: actions, path: jobs, as: step}
    lookup:
      - {as: k8s, kind: job, idSuffix: ":{step.k8sName}"}
      - {as: before, kind: job, idSuffix: ":{prev.k8sName}"}
    edges:
      - {from: "job:agenda:{name}", kind: runs, to: "{k8s._id}", attrs: {position: "{step._index} of {step._count}"}}
      - {from: "{before._id}", kind: after, to: "{k8s._id}"}
```

Read top to bottom: take every pipeline row; for each entry of `actions.jobs`, find the job whose id ends with
`:<k8sName>`; write "the pipeline runs it" and "it runs after the one before".

### Writing one

1. **Look at the facts you start from**: their ids and attributes are what you can use
   (`grep '"row:agenda' kb/*/*.jsonl | head -3`, or `describe` through MCP).
2. **Aim at ids logast already writes** (`service:<name>`, `job:<svc>:<name>`, `table:<schema>.<name>`,
   `property:<svc>:<key>`), so your facts join the graph instead of making islands.
3. **Try it**: `logast facts map --mapping kb/mappings/x.yaml kb/ --out /tmp/try.jsonl` prints each rule's facts and
   skips (with the first skip's reason).
4. **Ask why** for one fact: `--explain <id>` says, rule by rule, whether it matched (or which condition failed), its
   `each` elements, each lookup's node (or nothing), each fact written or the placeholder that had no value and the
   values there were.
5. **Pin it with a test**: `kb/mappings/tests/<name>.input.jsonl` (a few hand-written facts, one per case) and
   `logast facts map --test kb/mappings --update` writes `tests/<name>.expected.jsonl`; from then on
   `logast facts map --test kb/mappings` says ✓, or ✗ with the facts missing (`-`) and new (`+`).

### Reference

- **`needs`** (top level, beside `mappings`): `{rows: [schema.table, …]}`: the tables whose rows the mapping reads; a
  fleet run with `--flyway` exports them (else it says to add `--flyway`, or `rows:` to the repo in `repos.yaml`).
- **`match`**: `kind`, `idPrefix` (at least one), and `where`: attribute → a value (equal, as text) or conditions
  `{not: x}`, `{in: [a, b]}`, `{regex: p}` (found anywhere), `{contains: s}`, `{exists: true|false}`; all must hold.
  `_id`, `_kind`, `_env` can be tested too.
- **`each`**: `{attr, path, as}`: the JSON list at `path` (dotted) inside the attribute; `{as.<field>}`,
  `{as._index}`, `{as._count}`, and `{prev.<field>}` of the element before (nothing written, nothing reported, for
  the first one's `prev`).
- **`lookup`**: `{as, id}` or `{as, kind, idSuffix}` (`ignoreCase: true` compares without case, `where:` filters the
  nodes found, as above). A fact without environment matches nodes of every environment, and the facts are written
  once per environment found; one with an environment, that one's (or one for all).
- **Templates**: `{attribute}`, `{as.attribute}`, `{_id}`, `{_env}`, `{_kind}`, each with filters:
  `lower`, `upper`, `trim`, `default:'x'` (when there is none), `replace:'a':'b'`, `regex:'p'` (the first group, else
  the match; none when it does not match), `before:'s'` (before the first s), `after:'s'` (after the last s); e.g.
  `{step.k8sName | after:'-' | lower}`. An unknown filter is an error when the file is read. A template with a
  placeholder that has no (non-blank) value is skipped and counted, never written half-filled.
- **Mapped facts** keep the matched fact's environment and evidence (repo and commit made explicit), and say which
  rule made them (`attrs.mappedBy`).
- **Schedules through mappings**: a job without a schedule that a scheduled job `runs` (through the workloads it
  starts, up to four steps) gets that job's times in `next_runs`, and `runs_on` lists it below that job (#212).

## Your own facts

Knowledge no extractor or mapping covers (an internal API, a custom file format, a spreadsheet of owners) can be
written by any program, in any language, as a facts file next to logast's: `logast mcp` loads every `*.jsonl` under
its paths (#171).

- **One header line**, then nodes and edges (above). Name the producer in `extractor`, the repo the facts are about in
  `repo`.
- **Use the ids logast uses** where your facts are about the same things, so they join: `service:<name>`,
  `endpoint:<service>:<METHOD> <path>`, `job:<service>:<name>`, `table:<schema>.<name>`, `topic:<name>`,
  `secret:<vault>/<object>`, `deployment:<name>`. New kinds of your own are fine (`team:payments`, `owner-of`).
- **Evidence** names where a person can check the fact: a file in a repo, or (for an API) the source as `file`
  (`https://cmdb.example.com/services/42`).
- **Provenance**: `extracted` for what a source states, `inferred` (with `via` and a `confidence`) for what your program
  concludes.
- **Check before loading**: `logast facts validate <file>` in the job that writes it; a broken line names its number.
  The [JSON Schema](../modules/facts/src/main/resources/dev/logast/facts/kb-facts-0.schema.json) checks single lines in any
  language.

## Quartz jobs

A class that is a `org.quartz.Job` (or `InterruptableJob`, `StatefulJob`, Spring's `QuartzJobBean`) is a `job` with
`entrypoint: quartz` and its class as `handler` (#187): by type when the classpath has Quartz, else by what the source
names with an import of Quartz, and classes extending a job class of the repo too (an abstract base class is no job).
A class named where a job is scheduled (`setJobClass(X.class)`, `newJob(X.class)`, or a helper that builds the
JobDetail from a `Class` parameter, in a file importing Quartz; `X` resolved through its imports) is a job too, whatever its base class (a library's) (#208). A class with several
JobDetails (several `JobDetailFactoryBean`s, each with its own, different name) is one job per JobDetail:
`job:<svc>:<class>#<name>` (the method's name when it sets none), with `jobDetail` (the method), `jobName`,
`jobData` (the enum constants in it) and the cron of the trigger that sets that JobDetail.
Schedules are first found by following the code (#217): logast runs the configurations' `@Bean` and `@PostConstruct`
methods on the bound configuration, the way the application does at startup: `@ConfigurationProperties` classes bound
from their prefix (inherited fields, nested lists and enums, relaxed names), `List<T>` injected with every bean of T,
`@Bean` methods, constructors (Lombok's by field order), builders, getters, streams, `Optional`, maps, enum constants
with their constructor arguments. Each trigger it builds (`CronTriggerFactoryBean`, `TriggerBuilder…build()`) gives a
job (its `JobDetail`'s job class, an enum constant's argument included) its `cron` (with the key it came from), `zone`,
`name` and `group`. A chain: a global `JobListener` of the project is run as Quartz runs it after each job, with the
trigger's `JobDataMap`; the job it triggers next (`triggerJob`, or `scheduleJob` of a trigger `forJob` it), with the
data it passes on, is the next step (`chain`, `position`, `runsAfter`), with the values that data carries (a country,
a delay). A later step's times read "≥ 01:00 … after LoadJob" in `runs_on`, `next_runs` and code mode: it starts after
the job before it ends, at the earliest when the chain starts (#219). Lombok and Quartz need not be on the classpath. Job names listed under a configuration entry are no chain by
themselves: only code that runs them makes one.
Its `execute` (or `executeInternal`; else the methods it overrides of its base class) is where its calls, reads and
writes start, so `lineage`, `writers_of` and `readers_of` see them. Its `cron` is read where the code builds the schedule
next to the job's class: one method with `JobBuilder.newJob(X.class)` and `cronSchedule(…)`, or one configuration
class with one job and its trigger (`CronTriggerFactoryBean.setCronExpression`); a constant is read, a `${…}` resolved as
for `@Scheduled`. `runs_on` and `next_runs` read it in Quartz's dialect (seconds first, weekdays 1 = Sunday to 7, an
optional year). A schedule the configuration binds to the jobs (#189: `application.yml` read by a `@ConfigurationProperties` class
and scheduled by the service's own code) is read from the configuration: an entry (a list item or a map entry) with
a value naming the job (its class, simple name, bean name, or the entry's key, e.g. `report-job`) and a cron (a key
containing `cron`, or a value shaped like one) is the job's `cron`, with `cronFrom` naming the key; a profile's
`application-<profile>.yml` that sets another cron is in `cronByProfile`, and `runs_on`/`next_runs` in that
environment use it. First, the cron is followed back from the code that schedules the job (#198): a `setCronExpression(x)` or
`cronSchedule(x)` whose job is set beside it (`setJobDetail(bean)` → that bean's `setJobClass(X.class)`, or
`newJob(X.class)`), with `x` a literal or constant, a `@Value` key, or getters on a `@ConfigurationProperties` object,
also through a list filtered by a name (`props.getTriggers().stream().filter(t -> t.getName().equals(NAME))` →
`<prefix>.triggers[i].cron` where `name` is NAME's value); `cronFrom` names the key. A time zone the trigger sets (`setTimeZone(X)`, `inTimeZone(X)`, a constant such as
`TimeZone.getTimeZone("Europe/London")`) is followed the same way into `zone` (#199): `runs_on` and `next_runs` read
the cron in that zone and show the times in the one asked for. When the code does not say,
names in the configuration are matched: A job's entry may name it and list its triggers (#197: `jobs.<key>.name: StockEnrichmentJob` with
`jobs.<key>.triggers[i].cron`, each trigger with its own name and other keys): a cron is matched to the job named in
its own entry or in the entries above it (three levels at most), and a job with several triggers has them all in
`triggers` (name, cron, the other keys, where they came from); `runs_on` and `next_runs` list each trigger. A job named through an enum (#190) counts too: an enum constant whose arguments
name a job (`CLEANUP(CleanupJob.class, …)`, or the job's name), or the one constant a job's class refers to
(`JobType.ARCHIVE`), stands for that job (`enumConstant`); a configuration entry naming the constant (`CLEANUP`,
`cleanup`, `clean-up`, as Spring binds them) gives its cron, else a cron among the constant's arguments does. Jobs whose schedule lives in database rows link through a mapping on the row's class name
(`handler`).

## Configuration values

Every key a service's configuration sets is `property:<svc>:<key>` (#217), as Spring flattens it
(`batch.triggers.weekly-a.plans[0].chain[1].name`), with its `value` in the base files and `byProfile` where a
profile's file (`application-<profile>.yml`, a document activated for it) sets another; the evidence names the file
that sets it. A value that looks like a secret is never written (#183). These are the raw material for mappings
(a team's own rules join on them), and the `uses-property` edges of `@Value` and `@ConfigurationProperties` beans now
reach a node.

## The code's own values

Each enum constant is `enum-constant:<svc>:<Enum>.<NAME>` with `values`: what its constructor gives its fields
(`JobType.LOAD` → `jobClass`, `key`, `description`; Lombok's constructors by field order), and each `static final`
String or number is `constant:<svc>:<Class>.<NAME>` with its `value` (#215). Like configuration values, they are raw
material for mappings; `find` lists them after the nodes they are about (give `kind` to list them).

## Jobs chosen by a profile at startup

A service that runs one job per start, chosen by the active profile (#211), has a job per profile: a startup
entrypoint (a listener of `ApplicationReadyEvent`, `ApplicationStartedEvent` or `ContextRefreshedEvent`, a
`CommandLineRunner`/`ApplicationRunner`) that reaches two or more `@Profile` beans of a common type of the repo (an
abstract job class, an interface) gives `job:<svc>:<profile>` for each, with `entrypoint: startup`, the listener as
`handler`, the bean's class as `implementation` and `profile`. Its reads, writes and calls are what that class's own
methods reach (the listener keeps all of them). `@Profile` values are read from constants too
(`@Profile(JobNames.INVOICES)`). Profiles that name an environment (`local`, `dev`, `test`, `prod`, …) are not jobs.
A workload that starts the service with that profile active (a CronJob or Deployment whose `SPRING_PROFILES_ACTIVE`
lists it, e.g. `ppe,invoiceJob`) `runs` the job, inferred: through the service the workload is linked to
(`identity:profile`), else when only one service has a job for that profile (`identity:profile-name`).

## No secrets in facts

Facts are committed (the fleet's `kb/`) and read by an LLM, so they never carry a secret (#183). Where a literal value
reaches the facts (a chart's env var `value`, a `@Value` default, a Terraform attribute or tag, a `--rows` column), it is
replaced by `<redacted: looks like a secret>` when:

- **its name says secret**: `PASSWORD`, `PWD`, `SECRET`, `TOKEN`, `API_KEY`, `CLIENT_SECRET`, `CONNECTION_STRING`,
  `AUTH`, `SAS`, … (not names that only look like it: `TOKEN_URL`, `PASSWORD_MIN_LENGTH`, `JWT_EXPIRY_SECONDS`);
- **its shape is a credential**, whatever the name: a JWT, a PEM block, a URL with `user:password@`, a connection
  string with `Password=` / `AccountKey=` / `SharedAccessSignature`, known key formats (AWS, GitHub, Slack, Stripe,
  Google), a long random-looking string.

Kept as they are: test stubs and local defaults (`test`, `dummy`, `changeme`, `secret`, values starting with `test` or
`fake`, `localhost` URLs, credentials like `orders:test@localhost`), placeholders (`${…}`) and references
(`secret:<vault>/<object>`). `logast mcp`'s `risks` lists every redacted value as *a secret written in plain text*, with
where it was found: it belongs in a vault.

## Checking a file

```bash
logast facts validate facts/*.jsonl    # file:line: what is wrong; exit 1 when a line breaks a rule
```

A broken line is reported and left out; the others are read. For producers in other languages,
[`kb-facts-0.schema.json`](../modules/facts/src/main/resources/dev/logast/facts/kb-facts-0.schema.json) checks one line with
any JSON Schema validator (the rules across lines, header first and once, are the validator's).
