## What and why

<!-- Fixes #n / Refs #n -->

## Checklist

- [ ] Every change has a test that fails without it (switched off once and checked)
- [ ] e2e: one pass, second apply empty, no `CHECK FAILED`, result compiles; changed snapshots reviewed and explained
- [ ] `./gradlew build` and the touched scenarios (`./gradlew :cli:e2e --tests "*Name*"`) pass
- [ ] Each touched pack still works standalone (`--pack <pack>` alone)
- [ ] Docs updated: `docs/packs/<pack>.md`, `docs/rules.md`, `docs/configuration.md` / `docs/starter.md` as needed
- [ ] `docs/examples.md` updated when what `logast apply` writes changed (new example, or existing snippet adjusted)
- [ ] README only changed for stable overview content (no rule tables, counts or version lists)
- [ ] No company names, company code or internal identifiers (public repository)
