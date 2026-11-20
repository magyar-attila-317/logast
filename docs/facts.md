# Facts

A **fact** is a small, citable statement about code or deployment: *endpoint `GET /shipments/{id}` exists in
shipping, `ShipmentController.java:23`*; *this endpoint writes table `shipping.shipment`*. `logast export` writes the
facts its analysis finds; other extractors (Helm, Terraform, CI, or private ones) write the same format, so a tool
that loads them can join facts from many repos without knowing who produced them (#152).

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

Facts are sorted and repeats dropped, so the same code gives the same file.

## Checking a file

```bash
logast facts validate facts/*.jsonl    # file:line: what is wrong; exit 1 when a line breaks a rule
```

A broken line is reported and left out; the others are read. For producers in other languages,
[`kb-facts-0.schema.json`](../facts/src/main/resources/dev/logast/facts/kb-facts-0.schema.json) checks one line with
any JSON Schema validator (the rules across lines, header first and once, are the validator's).
