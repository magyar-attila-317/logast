# Asking an LLM about many repos (`logast mcp`)

`logast mcp` is an [MCP](https://modelcontextprotocol.io) server: any MCP client (an IDE assistant, a chat client, an
agent) can ask it questions over the [facts](facts.md) of many repos, and every answer cites the file and line it
comes from (#156).

## 1. Facts for every repo

`logast export` writes a repo's facts (read-only, as `scan` reads):

```bash
logast export path/to/shipping --out kb/shipping.jsonl
logast export path/to/billing --out kb/billing.jsonl
```

For a team, the fleet repo's workflow is the place: it already clones every repo of `repos.yaml` nightly, so it can
export each one into a folder of the fleet repo, and a developer's clone of that repo is their knowledge base.

## 2. Tell the server where they are

The first of these that is set wins:

1. `--kb <file or folder>` (repeatable) on `logast mcp`;
2. `LOGAST_KB`: paths separated like `PATH`;
3. `kb.paths` in the user config (`~/.config/logast/config.yaml`):

   ```yaml
   kb:
     paths:
       - ~/src/my-company-logast/kb
   ```

Every `*.jsonl` file below a folder is read. A file that changes (a `git pull` of the fleet repo) is read again on the
next question: no restart.

## 3. Add it to your MCP client

```bash
logast mcp config --kb ~/src/my-company-logast/kb
```

prints the server entry in the `mcpServers` shape most clients read (`command` + `args`, stdio); paste it into your
client's MCP configuration. `logast` must be on the `PATH` the client starts it with (else use its full path).

## What it can answer

| tool | asks |
|---|---|
| `summary` | what is loaded: files, repos, nodes and edges by kind |
| `find` | nodes whose id or attributes contain a text, optionally of one kind (`job`, `table`, `endpoint`, …) |
| `describe` | one node: attributes, evidence, edges in and out |
| `neighbors` | a node's edges by direction (out: what it does, in: what points at it) and kind (`calls`, `reads`, `writes`, `runs`, …) |
| `path` | the shortest chain from one node to another, e.g. from a profile to the job that runs it |
| `runs_on` | which jobs run on a day (a weekday, `today`, an ISO date), in an environment and time zone, at which times; the jobs that run after them in a pipeline; jobs whose schedule is not known, and why |
| `next_runs` | when a job runs next |
| `lineage` | what follows from a node (downstream: calls, messages and tables it writes to, who consumes and reads them, the next pipeline steps) or what leads to it (upstream), across services, as a tree with sources |
| `risks` | database handoffs between scheduled jobs of different services with no pipeline step between them (the reader relies on timing), tables several services write, reads of another service's schema |
| `secrets` | Key Vault secrets (Terraform, Helm) and who uses them: the workloads that mount or read them, and the services whose code runs there; `expiringDays` for those about to expire |
| `workflow_refs` | per reusable GitHub Actions workflow, the refs its callers use: who is still on an old one |
| `flows` | business flows: approved (knowledge.yml) and proposed, with their nodes; stale ones marked |
| `propose_flow`, `propose_summary` | the LLM proposes a flow (a name for a chain of nodes) or a node's summary, with sources; a person approves |
| `term` | a glossary term (or an alias, or an abbreviation): its definition and source |
| `read_name` | a technical name (`ord_inv_ref`, `ordInvRef`) as business words |
| `writers_of`, `readers_of` | every entrypoint that writes or reads a table (`schema.name`, or a name in any schema) |

For example: *who writes `audit.event`?* → every endpoint, listener and job that does, each with `repo/file:line@commit`.
*What runs on Monday in prod?* → computed from the jobs' cron schedules (#159): Unix five-field crons (a
mapping's jobs, CronJobs; when day-of-month and day-of-week are both set, either day fires) and Spring's six fields
(`@Scheduled`, seconds first; both must match), with names, ranges, steps and the `@daily` macros. A `@Scheduled`
cron with a `${…}` is resolved from the service's config, else the placeholder's default; Quartz's `L`, `W` and `#`
are named as not supported.

**Code and deployments joined** (#161): when the facts are loaded, a service (`service:<name>`, with the image its
build pushes when Jib's `to.image` or `bootBuildImage`'s `imageName` say it) is linked to the workloads that run it
(`deployment:<name>`, a CronJob's `job:…`) by an inferred `deployed-as` edge, per environment, by the strongest rule
that holds: the same image repository (confidence 0.95), the same name (0.8), the image's last path segment equal to
the service's name (0.7). A workload two services claim by the same rule is not linked; `summary` lists it.

**Across services** (#164): when the facts load, an HTTP call `reaches` the endpoints with its method and path (a
path variable matches any segment); its host decides between services that have the path: the service's name or
application name, a Kubernetes Service that routes its workload, or another name of it in `knowledge.yml` (0.9).
Without a host, one candidate is 0.6, several are 0.3 each; a host that names no service of the facts gives no link.
A table named without a schema in code is the `same-as` the one schema in the migrations that has it. `lineage`
follows these, Kafka topics and database tables, and an orchestrator's job through the profile to the code it runs.

**Risks** (#165): a database handoff is a table one service's scheduled job writes and another's reads; without a
pipeline step (`after`) from the writer's job to the reader's, the reader relies on timing. A job's schedule is its own
(`@Scheduled`, a CronJob) or that of the orchestrator's job that starts it (job → profile → bean → the entrypoint).
A service's own schemas are those where it writes tables no other service writes; reading another one is listed.

## What people know: `knowledge.yml`

Facts say what code and deployments contain. What no file proves (what *ord* stands for, that *order-service* is
what others call *orders*, whether an inferred link is right) goes into a `knowledge.yml` (or `.yaml`) anywhere under
the kb paths, written by people and applied when the facts load (#163). It is named so it is not confused with
logast's `company.yaml` policy in the same fleet repo.

```yaml
glossary:
  order:
    definition: A customer's request to buy, from checkout to delivery.
    source: https://wiki.example.com/glossary#order
    aliases: [purchase]
  invoice: The bill sent for an order.            # a definition alone
abbreviations:
  ord: order
  inv: invoice
services:
  orders: [order-service, ord-svc]                # other names of a service: linked to workloads of those names
decisions:
  - link: "service:billing -deployed-as-> deployment:billing-worker"
    env: prod                                     # optional: every environment when left out
    decision: reject                              # or approve
    by: jane
    evidence: deploy/charts/billing/templates/deployment.yaml   # what it was decided on (optional)
```

- **`services`**: a workload named like another name of a service is linked to it (`identity:alias`, 0.9).
- **`decisions`**: a rejected link is dropped, an approved one is marked (`decision=approved by …`). When the link's
  evidence no longer starts with the `evidence` it was decided on, the decision is **stale**: the link stays inferred,
  marked, and `summary` lists it to decide again, rather than applying an old decision to new facts.
- **Names as business words**: `describe` shows how a name reads (`table:billing.ord_inv_ref` → *order invoice
  reference*), through the abbreviations and the glossary's aliases; tools `term` and `read_name` ask directly.

## What the LLM adds: proposals

Facts give chains; what they mean for the business (*month-end settlement*) and what a shell script does are an
LLM's work. logast holds no LLM: the team's client does it with `propose_flow` (a name, a description, the node ids in
order, the sources it rests on) and `propose_summary` (a node, a text, sources) (#168). They are written to
`knowledge.proposed.yml` next to the first `knowledge.yml` under the kb paths (else in the first kb folder): a file
logast manages, so `knowledge.yml` stays the people's.

- Every proposal records `by: llm`, when, its sources, and the evidence of its nodes then; when that evidence changes,
  `flows` and `describe` mark it **stale**. A node the facts do not have, or a proposal without sources, is refused.
- **Approving** is moving the entry to `knowledge.yml`'s `flows:` or `summaries:` (same shape); an approved entry wins
  over a proposal of the same name.
- `flows` lists both kinds; `describe` shows a node's summary and the flows it belongs to; `lineage` accepts a flow's
  name and starts from each of its nodes.
- Started without a kb path (or from a client that cannot write there), the server is read-only and says so.

The server speaks MCP's stdio transport (JSON-RPC 2.0, one message per line: `initialize`, `tools/list`,
`tools/call`, `ping`); it writes diagnostics to stderr only.
