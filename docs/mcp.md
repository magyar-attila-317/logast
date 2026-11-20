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
| `writers_of`, `readers_of` | every entrypoint that writes or reads a table (`schema.name`, or a name in any schema) |

For example: *who writes `audit.event`?* → every endpoint, listener and job that does, each with `repo/file:line@commit`.
*What runs on Monday in prod?* → computed from the jobs' cron schedules (#159): Unix five-field crons (a
mapping's jobs, CronJobs; when day-of-month and day-of-week are both set, either day fires) and Spring's six fields
(`@Scheduled`, seconds first; both must match), with names, ranges, steps and the `@daily` macros. A `@Scheduled`
cron with a `${…}` is resolved from the service's config, else the placeholder's default; Quartz's `L`, `W` and `#`
are named as not supported.

The server speaks MCP's stdio transport (JSON-RPC 2.0, one message per line: `initialize`, `tools/list`,
`tools/call`, `ping`); it writes diagnostics to stderr only.
