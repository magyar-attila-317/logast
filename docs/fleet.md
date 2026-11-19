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

1. **Create** the fleet: `logast fleet create <name>` makes its folder (names: letters, digits, `.`, `-`, `_`).
2. **Join** in each service: `logast fleet join <name> [service root]` writes one line into the service's
   `logast.yaml`, `fleet: <name>` (creating the file if there is none; the rest of the file and its comments stay), and
   registers the service in the fleet folder. The name is the only thing in the repository: it is committed, and each
   machine finds the fleet's folder itself (below). A logast.yaml with nothing but `fleet:` (and `extends:`) counts
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
5. **Decide the vocabulary** in the fleet's worksheet (below); every member takes the decided terms on its next run.

`logast fleet leave [service root]` takes a service out: the `fleet:` line, its member entry and its snapshot go.
`logast fleet list` shows the fleets this machine knows, their folders and members.

## The overview

```text
fleet payments-fleet  ~/.local/share/logast/fleets/payments-fleet  (2 members)
  checkout  score  21.6  open   4 (high 2, medium 1, low 1)  snapshot 3 min ago, 4f2a9c1
  payments  score  42.1  open   5 (high 0, medium 2, low 3)  snapshot 2 h ago, 77b01de

Trace across services: 1 call reaches another service, 1 drops the trace
  checkout -> payments  POST /payments
    call   src/main/java/com/example/checkout/PaymentClient.java:16  RestClient, PaymentClient.pay
    entry  src/main/java/com/example/payments/PaymentController.java:15  POST /payments, PaymentController.pay
    breaks at the caller (checkout): RestClient in a field initializer: no traceparent header on outgoing calls
      fix: order HTC-f572b7 (HTTP_CLIENT_UNTRACED) in checkout: Build it in the constructor from an injected, …
One outcome, several metric names: 0 candidates (pick one name; nothing is merged)

Vocabulary: 1 conflict between keys nobody decided yet
  paymentId ~ payment_id: same type Long, source Payment.id(); differs: services checkout vs payments
  to decide: 2 (2 new): paymentId (new), payment_id (new)
```

- Per member: score (as in the [fleet report](commands.md#fleet)), open findings, the snapshot's age and commit. A
  member without a snapshot says where to run `logast scan`.
- **Trace across services** and **one outcome, several metric names**: the same comparison as `logast fleet <paths…>`
  ([commands](commands.md#fleet)), made from the snapshots.
- **Vocabulary**: keys nobody decided yet that look like one value under two names (same identity, source or type)
  or one name with different types, and what is left to decide.

Options: `--refresh` rescans every member first (from the roots in the member list; a member not at its root on this
machine keeps its snapshot); `--html` also writes the report page to `<fleet>/report/fleet.html` (or `--out <dir>`),
with the trend against the previous page; `--json` prints the overview as JSON.

## The fleet's vocabulary

`logast fleet <name> --vocabulary` builds the [vocabulary worksheet](vocabulary.md#one-vocabulary-across-services)
from the members' snapshots, keeping the decisions already in it, and regenerates the fleet's `company.yaml`:

| file in the fleet folder | holds | edited by |
|---|---|---|
| `vocabulary-worksheet.yaml` | every key of every member with its evidence, and your decisions (`term`, `same_as`, `local`, `owner`, `area`) | you decide; logast fills in the evidence |
| `company.yaml` | **generated** from the decided terms | logast only |
| `members.yaml` | service → its root on this machine | `join` / `leave` |
| `services/<service>.json` | the members' snapshots | every scan and apply of a member |

A member's `logast.yaml` with `fleet: <name>` extends the fleet's `company.yaml` as if it were the first entry of
`extends:` (the file's own `extends:` and settings come after it and win). Nothing is copied into the service, so a
decision reaches every member on its next run: `scan` reports the new aliases and `apply` renames them
(MDC_KEY_ALIAS). A fleet this machine does not know is a policy warning; the service's own rules still apply.

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
