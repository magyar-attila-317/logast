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

## What `logast export` writes

`logast export <service> [--out facts.jsonl] [--repo <name>]` scans the service (read-only, as `scan` does) and writes
(#153):

| fact | id | attrs / edges |
|---|---|---|
| the service | `service:<spring.application.name, else the folder>` | `application`, `package`, `springBoot`; `has` each entrypoint, `depends-on` each library |
| an HTTP endpoint | `endpoint:<svc>:<METHOD> <path>` | `method`, `path`, `handler`, `observed` |
| a listener | `listener:<svc>:<kafka\|rabbit\|jms> <destination>`, other kinds `listener:<svc>:<kind> <label>` | `destination`, `handler`; `consumes` `topic:<name>` (Kafka) or `queue:<name>` |
| a job | `job:<svc>:<Class.method>` (scheduled, runners, batch steps), `job:<svc>:<class>` (Quartz) | `cron` / `fixedRate` / `fixedDelay` / `zone` as written (placeholders kept: each environment resolves them) |
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
- **Placeholders** (`${schema}`): `spring.flyway.placeholders.*` of the environment's profile
  (`application-<env>.yml`) over the base config. A placeholder with no value runs with its own name; a placeholder
  used as a schema (`${schema}.jobs`) gets that schema created first.
- **Facts**: `table:<schema>.<name>` per environment (`env`), with its `columns`, the migration's `create table` line
  as evidence; with `--rows`, each row of those tables as `row:<schema>.<table>:<primary key>` (else `#<position>`),
  its columns as attributes: a scheduler's job definitions, for example.
- An environment whose migrations fail is named on stderr, with the placeholders that had no value; the others are
  still written.

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
- **Layout**: units under `terragrunt/<subscription>/<region>/<env>/…`: the environment is the segment after the
  region. The module is the folder the unit's `source` names under `terraform/` (`…/terraform//<module>`); one level of
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
- `logast mcp`'s `workflow_refs` lists, per reusable workflow, the refs its callers use.

## Self-hosted runners (Ansible)

A repo with an Ansible inventory (INI or YAML, under `inventory/` or named `hosts`/`inventory`) also gets its runner
hosts (#173): every host whose variables (inline, `group_vars`, `host_vars`, through parent groups) set runner labels
(`runner_labels`, `github_runner_labels`, …; a list or a comma string) is `runner-host:<host>`, with its `groups`,
`labels` and `count` (`runner_count`, `runners_per_host`). When facts load, a workflow on `self-hosted` labels
`runs-on` the hosts whose labels cover them (GitHub's default labels, OS and architecture, need no match); `logast mcp`'s
`runners` lists hosts, their workflows, and self-hosted workflows no runner fits.

## Company-specific facts: `logast facts map`

Some knowledge is your company's own: a scheduler that keeps its job definitions in database rows, a convention for
naming things. `logast facts map --mapping <file.yaml> <facts files or folders…> [--out file]` turns facts into more
facts with rules from a mapping file, so that knowledge needs no code in logast and the file stays in your own repo
(#158). For example, with the rows `export --flyway --rows` wrote:

```yaml
mappings:
  - name: jobs
    match: {kind: row, idPrefix: "row:orchestration.job_definition:", where: {enabled: "true"}}
    nodes:
      - {id: "job:{service}:{name}", kind: job, attrs: {schedule: "{cron}", scheduleFormat: unix}}
    edges:
      - {from: "service:{service}", kind: has, to: "job:{service}:{name}"}
      - {from: "job:{service}:{name}", kind: runs, to: "profile:{service}:{profile}"}   # joins the @Profile bean
  - name: pipeline
    match: {kind: row, idPrefix: "row:orchestration.pipeline_step:"}
    lookup:                                   # other nodes by id: their attributes as {as.attribute}
      - {as: job, id: "row:orchestration.job_definition:{job_id}"}
      - {as: next, id: "row:orchestration.job_definition:{next_job_id}"}
    edges:
      - {from: "job:{job.service}:{job.name}", kind: after, to: "job:{next.service}:{next.name}"}
```

- **`match`**: a node `kind`, an `idPrefix`, and `where` (attribute equals a value, as text); at least a kind or a
  prefix.
- **Templates**: `{attribute}` of the matched node, `{as.attribute}` of a lookup, `{_id}`, `{_env}`, `{_kind}`. A
  template with a placeholder that has no (non-blank) value is skipped and counted on stderr, never written
  half-filled; a lookup finds the node of the same environment (or one for all).
- **Mapped facts** keep the matched fact's environment and evidence (with its repo and commit made explicit), and say
  which rule made them (`attrs.mappedBy`). Write them next to the other facts files; `logast mcp` loads them all.

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
  The [JSON Schema](../facts/src/main/resources/dev/logast/facts/kb-facts-0.schema.json) checks single lines in any
  language.

## Checking a file

```bash
logast facts validate facts/*.jsonl    # file:line: what is wrong; exit 1 when a line breaks a rule
```

A broken line is reported and left out; the others are read. For producers in other languages,
[`kb-facts-0.schema.json`](../facts/src/main/resources/dev/logast/facts/kb-facts-0.schema.json) checks one line with
any JSON Schema validator (the rules across lines, header first and once, are the validator's).
