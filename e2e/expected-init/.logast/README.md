# .logast

Files logast writes for this service. You rarely edit them by hand.

| file | what it is | in git? |
|---|---|---|
| `classpath-*.txt` | the service's compile classpath, written by `logast classpath .` (Gradle runs once). logast needs it to understand types. Run it again after you change dependencies. | no: paths are machine-specific |
| `traffic.yaml` | every entrypoint of the service, with calls per day if you fill them in. Used to judge log volume (LOG_VOLUME). Leave a value empty if you don't know it. Refresh the list with `logast traffic .`. | yes |
| `baseline.json` | the findings that existed when it was recorded (`logast scan . --update-baseline`). CI uses it to fail only on *new* findings: `logast scan . --baseline --fail-on high`. | yes |
| `orders.json` | the result of the last `logast scan` (full detail, read by `apply` and `review`). | no |
| `verify.json` | the result of the last `logast verify`. | no |

Configuration lives in `logast.yaml` next to this folder: https://github.com/magyar-attila-317/logast/blob/main/docs/configuration.md
