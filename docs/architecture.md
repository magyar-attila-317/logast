# Discover, derive, map: where knowledge belongs

logast builds a knowledge base of a fleet of repos. What goes into it comes from three layers, and each piece of
knowledge belongs in exactly one of them (#215).

| Layer | Who owns it | What it holds | How to tell it belongs here |
|---|---|---|---|
| **1. Discover** (extractors) | logast | what code and configuration *state*, read through documented conventions: Spring, Quartz, Flyway, GitHub Actions, Helm/Kubernetes, Terraform | the framework's documentation says it works this way; another team's repo would be read the same way |
| **2. Derive** (knowledge base) | logast | links that general rules justify: an image → the deployment running it, an active profile → the job it starts, an HTTP call → the endpoint it reaches, a workflow's variables → each caller | the rule holds in any fleet; the link is `inferred`, with `via` and a confidence |
| **3. Map** (mappings) | your team, in your private fleet repo | what only knowledge of your setup gives: a scheduler's rows, a naming convention, which team owns what | it names your tables, keys or conventions |

**The contract between them** is the facts format (`kb-facts/0`, [facts.md](facts.md)) and logast's id conventions
(`service:<name>`, `job:<svc>:<name>`, `table:<schema>.<name>`, `property:<svc>:<key>`, …). Mappings read facts and
write facts; they never change logast, and logast's code never holds one team's layout.

## Rules

- **Follow the code, do not guess from names.** logast resolves what the application resolves, the way it does: config
  binding (Spring's relaxed names), injected beans, constants, calls, framework APIs (the tracer, #217). An unusual
  setup is fine; a guess from the shape of keys or names is not. Where the code cannot be followed (reflection, values
  read at runtime), the facts say so instead of guessing.
- **Discover deep and raw.** Configuration values, constants, seed rows are facts in their own right, so a team's
  rule is a short join over them instead of new extractor code.
- **A team rule that is hard to write as a mapping means a raw fact is missing in layer 1** — never a team heuristic in
  an extractor. Then the mapping language gains the generic capability it lacks (`each`, `idSuffix`, filters).
- **Name heuristics in the code are stopgaps** (the name matching of #197 / #190): marked as such, replaced by
  following the code when the shape is known.

## Where each piece lives

- Extractors: `core/src/main/java/dev/logast/export/` (Java, Flyway, Helm, Terraform, workflows); the tracer:
  `core/src/main/java/dev/logast/trace/`.
- Derived links: `kb/src/main/java/dev/logast/kb/` (`Identity`, `Lineage`, `WorkflowChains`).
- Mappings: `kb/mappings/*.yaml` in a fleet ([facts.md](facts.md#company-specific-facts-logast-facts-map)), applied by
  `logast fleet … --kb` into `kb/mapped/`; tested with `logast facts map --test`.
