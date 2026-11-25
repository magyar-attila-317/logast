# Corpus decisions (#328)

One file per service of `../corpus.yaml`: the judgement calls the first apply made on it (log lines added by level,
with or without the exception; existing lines given the exception; every metric name and family). `./gradlew
:cli:corpus` compares them and fails on a difference, so a rule that drifts shows here as a diff instead of in
someone's dry run. After a deliberate change, rewrite them with `-Pcorpus.updateDecisions` (or the corpus workflow
with "update" ticked, then download them from its artifact) and commit them with the change that explains the diff.
