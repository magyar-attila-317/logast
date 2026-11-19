# .logast

Files logast keeps for this service. `decisions/` holds what a person decides (you edit these, they go into
git); `reports/` holds what logast writes for you to read (rewritten on every run, not in git).

| file | what it is | in git? |
|---|---|---|
| `decisions/messages.yaml` | the message texts only a person can write (MSG), one entry per finding that needs one. `scan` and `apply` keep it up to date and keep what you wrote; `logast review . --open` edits it in a browser. | yes |
| `decisions/metrics.yaml` | per logged outcome: count it (`counter`, `tag`) or `skip` it (metrics pack). Kept up to date like messages.yaml. | yes |
| `decisions/traffic.yaml` | every entrypoint of the service, with calls per day if you fill them in. Used to judge log volume (LOG_VOLUME). Leave a value empty if you don't know it. Refresh the list with `logast traffic .`. | yes |
| `decisions/retry.yaml` | every `@Retryable` usage (resilience pack). By default they share one block of retry settings in `application.yml`; `specific: true` gives a usage its own block on the next `apply`. | yes |
| `baseline.json` | the findings that existed when it was recorded (`logast scan . --update-baseline`). CI uses it to fail only on *new* findings: `logast scan . --baseline --fail-on high`. | yes |
| `classpath-*.txt` | the service's compile classpath, written by `logast classpath .` (Gradle runs once). logast needs it to understand types. Run it again after you change dependencies. | no: paths are machine-specific |
| `reports/orders.json` | the result of the last `logast scan` (full detail, read by `apply` and `review`). | no |
| `reports/coverage.txt` | every log call and what logast did with it: fixed, reported, ignored, skipped (with the reason) or checked. Written by `scan` and `apply --write` with the logging pack. | no |
| `reports/steps/`, `reports/apply-trace.txt`, `reports/last-apply.txt` | the last `apply`: one diff per kind of change, what each round did, what it changed. | no |
| `reports/metrics.md`, `reports/queries.md`, `reports/lifecycle.txt` | what the service measures (and what it does not), dashboard and alert queries (PromQL, NRQL, Splunk), every flow's start and outcome lines. Written by every `scan` and `apply`; `logast explain metrics` (`queries`, `lifecycle`) prints them. | no |
| `reports/verify.json` | the result of the last `logast verify`. | no |

Configuration lives in `logast.yaml` next to this folder: https://github.com/magyar-attila-317/logast/blob/main/docs/configuration.md
