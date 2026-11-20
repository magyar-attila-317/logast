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
| an entity | `entity:<svc>:<class>` | `name` (for JPQL), `table`; `maps` `table:<schema.>name>` (`@Table`, else Spring Boot's default naming: `OrderLine` → `order_line`) |
| a table read or written (#154) | `table:<schema>.<name>`, or `table:<name>` when the code names no schema | `reads` / `writes` from each entrypoint that reaches the access (or from the service, `reached: false`), `via` (`InvoiceRepository.save`, `JdbcTemplate.update`); `parsed: false` when the SQL was read from its keywords |
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

## Checking a file

```bash
logast facts validate facts/*.jsonl    # file:line: what is wrong; exit 1 when a line breaks a rule
```

A broken line is reported and left out; the others are read. For producers in other languages,
[`kb-facts-0.schema.json`](../facts/src/main/resources/dev/logast/facts/kb-facts-0.schema.json) checks one line with
any JSON Schema validator (the rules across lines, header first and once, are the validator's).
