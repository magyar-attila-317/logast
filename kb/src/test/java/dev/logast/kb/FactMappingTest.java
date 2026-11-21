package dev.logast.kb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.facts.Edge;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #158: company-specific facts from a mapping file: a scheduler's job and pipeline rows become jobs and edges. */
class FactMappingTest {
    @TempDir
    Path tmp;

    private static final String ROWS = """
            {"header":{"schema":"kb-facts/0","extractor":"java+flyway","repo":"scheduler","commit":"0123456789"}}
            {"node":{"id":"row:orchestration.job_definition:1","kind":"row","env":"dev","attrs":{"id":1,"name":"nightly-export","service":"shipping","profile":"export","cron":"*/10 * * * *","enabled":true},"evidence":{"file":"db/migration/common/V1__jobs.sql","line":7}}}
            {"node":{"id":"row:orchestration.job_definition:1","kind":"row","env":"prod","attrs":{"id":1,"name":"nightly-export","service":"shipping","profile":"export","cron":"0 2 * * *","enabled":true},"evidence":{"file":"db/migration/common/V1__jobs.sql","line":7}}}
            {"node":{"id":"row:orchestration.job_definition:2","kind":"row","env":"prod","attrs":{"id":2,"name":"settlement","service":"billing","profile":"tally","cron":"30 5 * * *","enabled":true},"evidence":{"file":"db/migration/common/V1__jobs.sql","line":8}}}
            {"node":{"id":"row:orchestration.job_definition:3","kind":"row","env":"prod","attrs":{"id":3,"name":"legacy","service":"billing","profile":"","cron":"0 0 * * *","enabled":false},"evidence":{"file":"db/migration/prod/V2__prod.sql","line":1}}}
            {"node":{"id":"row:orchestration.pipeline_step:10","kind":"row","env":"prod","attrs":{"id":10,"job_id":1,"next_job_id":2},"evidence":{"file":"db/migration/prod/V3__pipeline.sql","line":2}}}
            {"node":{"id":"row:orchestration.pipeline_step:11","kind":"row","env":"prod","attrs":{"id":11,"job_id":2,"next_job_id":99},"evidence":{"file":"db/migration/prod/V3__pipeline.sql","line":3}}}
            """;

    private static final String MAPPING = """
            mappings:
              - name: jobs
                match: {kind: row, idPrefix: "row:orchestration.job_definition:", where: {enabled: "true"}}
                nodes:
                  - {id: "job:{service}:{name}", kind: job, attrs: {schedule: "{cron}", scheduleFormat: unix}}
                edges:
                  - {from: "service:{service}", kind: has, to: "job:{service}:{name}"}
                  - {from: "job:{service}:{name}", kind: runs, to: "profile:{service}:{profile}"}
              - name: pipeline
                match: {kind: row, idPrefix: "row:orchestration.pipeline_step:"}
                lookup:
                  - {as: job, id: "row:orchestration.job_definition:{job_id}"}
                  - {as: next, id: "row:orchestration.job_definition:{next_job_id}"}
                edges:
                  - {from: "job:{job.service}:{job.name}", kind: after, to: "job:{next.service}:{next.name}"}
            """;

    private static final class Collect implements FactSink {
        final List<Node> nodes = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();

        @Override
        public void node(Node n) {
            nodes.add(n);
        }

        @Override
        public void edge(Edge e) {
            edges.add(e);
        }
    }

    @Test
    void rowsBecomeJobsAndPipelineEdges() throws IOException {
        Files.writeString(tmp.resolve("scheduler.jsonl"), ROWS);
        KnowledgeBase kb = KnowledgeBase.load(List.of(tmp));
        FactMapping m = FactMapping.parse(new ObjectMapper(new YAMLFactory()).readTree(MAPPING));
        Collect c = new Collect();
        FactMapping.Stats stats = m.apply(kb, c);

        assertThat(c.nodes).extracting(n -> n.id() + " [" + n.env() + "] " + n.attrs().get("schedule")).containsExactlyInAnyOrder(
                "job:shipping:nightly-export [dev] */10 * * * *",
                "job:shipping:nightly-export [prod] 0 2 * * *",
                "job:billing:settlement [prod] 30 5 * * *");
        assertThat(c.nodes).as("a disabled job is filtered out by where").noneMatch(n -> n.id().contains("legacy"));
        Node prod = c.nodes.stream().filter(n -> "prod".equals(n.env()) && n.id().equals("job:shipping:nightly-export")).findFirst().orElseThrow();
        assertThat(prod.attrs()).containsEntry("scheduleFormat", "unix").containsEntry("mappedBy", "jobs");
        assertThat(prod.evidence().repo()).as("the source's repo and commit, made explicit").isEqualTo("scheduler");
        assertThat(prod.evidence().commit()).isEqualTo("0123456789");
        assertThat(prod.evidence().line()).isEqualTo(7);

        assertThat(c.edges).extracting(e -> e.from() + " -" + e.kind() + "-> " + e.to() + " [" + e.env() + "]").contains(
                "service:shipping -has-> job:shipping:nightly-export [prod]",
                "job:shipping:nightly-export -runs-> profile:shipping:export [dev]",
                "job:billing:settlement -runs-> profile:billing:tally [prod]",
                "job:shipping:nightly-export -after-> job:billing:settlement [prod]");
        assertThat(stats.skipped()).as("a pipeline step pointing at a job that does not exist").containsEntry("pipeline", 1);
        assertThat(stats.skippedExample().get("pipeline")).contains("row:orchestration.pipeline_step:11");
        assertThat(stats.written()).containsEntry("jobs", 9).containsEntry("pipeline", 1);
    }

    @Test
    void aMappingThatCannotWorkSaysWhy() {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        assertThatThrownBy(() -> FactMapping.parse(yaml.readTree("other: 1"))).hasMessageContaining("needs a 'mappings:' list");
        assertThatThrownBy(() -> FactMapping.parse(yaml.readTree("mappings:\n  - name: x\n    match: {}\n    nodes: [{id: a, kind: b}]")))
                .hasMessageContaining("match needs a kind or an idPrefix");
        assertThatThrownBy(() -> FactMapping.parse(yaml.readTree("mappings:\n  - name: x\n    match: {kind: row}")))
                .hasMessageContaining("writes nothing");
        assertThatThrownBy(() -> FactMapping.parse(yaml.readTree("mappings:\n  - name: x\n    match: {kind: row}\n    edges: [{from: a, to: b}]")))
                .hasMessageContaining("'kind' is required");
    }

    @Test
    void placeholdersNeedAValue() {
        assertThat(FactMapping.fill("job:{service}:{name}", java.util.Map.of("service", "s", "name", "n"))).isEqualTo("job:s:n");
        assertThat(FactMapping.fill("job:{service}:{name}", java.util.Map.of("service", "s"))).isNull();
        assertThat(FactMapping.fill("job:{service}", java.util.Map.of("service", " "))).as("blank is no value").isNull();
        assertThat(FactMapping.fill("a$b {x}", java.util.Map.of("x", "$1"))).isEqualTo("a$b $1");
    }

    /**
     * #212: a pipeline row lists its steps in a JSON column; each step names the workload it starts (the workload's id
     * has a release in front); the rows have no environment, the workloads do. The service's job then runs when the
     * pipeline does.
     */
    @Test
    void pipelineStepsInAJsonColumnRunTheWorkloadsTheyName() throws IOException {
        String quote = "\\\"";
        String actions = "{" + quote + "jobs" + quote + ":[{" + quote + "type" + quote + ":" + quote + "K8S_JOB" + quote + "," + quote + "k8sName"
                + quote + ":" + quote + "billing-invoicejob" + quote + "},{" + quote + "type" + quote + ":" + quote + "K8S_JOB" + quote + ","
                + quote + "k8sName" + quote + ":" + quote + "billing-reminderjob" + quote + "}]}";
        Files.writeString(tmp.resolve("agenda.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java+flyway","repo":"agenda"}}
                {"node":{"id":"row:agenda.routine:1","kind":"row","attrs":{"id":1,"name":"NightlyBilling","run_mode":"CRON","run_spec":"0 0 1 * * MON","actions":"%s"},"evidence":{"file":"db/migration/V5__pipelines.sql","line":3}}}
                """.formatted(actions));
        Files.writeString(tmp.resolve("deploy.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"helm","repo":"deploy"}}
                {"node":{"id":"job:platform:billing-invoicejob","kind":"job","env":"ppe","attrs":{"entrypoint":"cronjob","schedule":"0 0 1 1 *","suspended":true},"evidence":{"file":"charts/billing/job.yaml"}}}
                {"node":{"id":"job:platform:billing-reminderjob","kind":"job","env":"ppe","attrs":{"entrypoint":"cronjob","schedule":"0 0 1 1 *","suspended":true},"evidence":{"file":"charts/billing/job.yaml"}}}
                {"edge":{"from":"job:platform:billing-invoicejob","kind":"runs","to":"job:billing:invoiceJob","env":"ppe","provenance":"extracted","evidence":{"file":"charts/billing/job.yaml"}}}
                {"node":{"id":"job:billing:invoiceJob","kind":"job","attrs":{"entrypoint":"startup","profile":"invoiceJob"},"evidence":{"file":"InvoiceJob.java"}}}
                """);
        FactMapping m = FactMapping.parse(new ObjectMapper(new YAMLFactory()).readTree("""
                mappings:
                  - name: pipelines
                    match: {kind: row, idPrefix: "row:agenda.routine:", where: {run_mode: CRON}}
                    nodes:
                      - {id: "job:agenda:{name}", kind: job, attrs: {schedule: "{run_spec}", scheduleFormat: spring, entrypoint: pipeline}}
                  - name: pipeline-steps
                    match: {kind: row, idPrefix: "row:agenda.routine:"}
                    each: {attr: actions, path: jobs, as: step}
                    lookup:
                      - {as: k8s, kind: job, idSuffix: ":{step.k8sName}"}
                      - {as: before, kind: job, idSuffix: ":{prev.k8sName}"}
                    edges:
                      - {from: "job:agenda:{name}", kind: runs, to: "{k8s._id}", attrs: {position: "{step._index} of {step._count}"}}
                      - {from: "{before._id}", kind: after, to: "{k8s._id}"}
                """));
        Collect c = new Collect();
        FactMapping.Stats stats = m.apply(KnowledgeBase.load(List.of(tmp)), c);
        assertThat(stats.skipped()).as("the first step has none before it: not a skip").isEmpty();
        assertThat(c.edges).extracting(e -> e.from() + " -" + e.kind() + "-> " + e.to() + " [" + e.env() + "] " + e.attrs().getOrDefault("position", ""))
                .containsExactlyInAnyOrder(
                        "job:agenda:NightlyBilling -runs-> job:platform:billing-invoicejob [ppe] 1 of 2",
                        "job:agenda:NightlyBilling -runs-> job:platform:billing-reminderjob [ppe] 2 of 2",
                        "job:platform:billing-invoicejob -after-> job:platform:billing-reminderjob [ppe] ");

        Path mapped = Files.createDirectories(tmp.resolve("mapped"));
        try (var w = Files.newBufferedWriter(mapped.resolve("agenda.jsonl"));
             var fw = new dev.logast.facts.FactWriter(w, dev.logast.facts.Header.of("mapping", "1", "mapped", null))) {
            c.nodes.forEach(fw::node);
            c.edges.forEach(fw::edge);
        }
        KnowledgeBase kb = KnowledgeBase.load(List.of(tmp));
        String next = Schedules.nextRuns(kb, "job:billing:invoiceJob", "ppe", 2, "UTC",
                java.time.ZonedDateTime.of(2026, 11, 21, 12, 0, 0, 0, java.time.ZoneOffset.UTC));
        assertThat(next).as("the pipeline's times, not those of the CronJob it starts")
                .contains("run by job:agenda:NightlyBilling", "Mon 2026-11-23 01:00:00").doesNotContain("01-01");
        assertThat(Schedules.runsOn(kb, "2026-11-23", "ppe", "UTC", java.time.LocalDate.of(2026, 11, 21)))
                .contains("job:agenda:NightlyBilling", "job:platform:billing-invoicejob, run by job:agenda:NightlyBilling",
                        "job:billing:invoiceJob, run by job:platform:billing-invoicejob");
    }

    /** #213: filters in templates, conditions in where (also a lookup's), and what --explain says about one node. */
    @Test
    void filtersConditionsAndExplain() throws IOException {
        Files.writeString(tmp.resolve("rows.jsonl"), """
                {"header":{"schema":"kb-facts/0","extractor":"java+flyway","repo":"scheduler"}}
                {"node":{"id":"row:sched.step:1","kind":"row","attrs":{"workload":"billing-svc-InvoiceJob","type":"K8S_JOB","owner":"Billing Team"},"evidence":{"file":"V1.sql"}}}
                {"node":{"id":"row:sched.step:2","kind":"row","attrs":{"workload":"billing-svc-reminderjob","type":"HTTP"},"evidence":{"file":"V1.sql"}}}
                {"node":{"id":"row:sched.step:3","kind":"row","attrs":{"workload":"orders-svc-exportjob","type":"K8S_JOB"},"evidence":{"file":"V1.sql"}}}
                {"node":{"id":"job:billing:invoiceJob","kind":"job","attrs":{"entrypoint":"startup","profile":"invoiceJob"},"evidence":{"file":"InvoiceJob.java"}}}
                {"node":{"id":"job:orders:exportJob","kind":"job","attrs":{"entrypoint":"quartz"},"evidence":{"file":"ExportJob.java"}}}
                """);
        FactMapping m = FactMapping.parse(new ObjectMapper(new YAMLFactory()).readTree("""
                mappings:
                  - name: steps
                    match: {kind: row, idPrefix: "row:sched.step:", where: {type: {in: [K8S_JOB, CRON_JOB]}, workload: {regex: "-svc-"}}}
                    lookup:
                      - {as: job, kind: job, idSuffix: ":{workload | after:'-' | lower}", ignoreCase: true, where: {entrypoint: startup}}
                    nodes:
                      - {id: "team:{owner | lower | replace:' ':'-' | default:'unowned'}", kind: team}
                    edges:
                      - {from: "row:sched.step:{_id | after:':'}", kind: starts, to: "{job._id}"}
                """));
        Collect c = new Collect();
        List<String> trace = new ArrayList<>();
        FactMapping.Stats stats = m.apply(KnowledgeBase.load(List.of(tmp)), c, "row:sched.step:3", trace);
        assertThat(c.nodes).extracting(Node::id).as("lower, replace, default").containsExactlyInAnyOrder("team:billing-team", "team:unowned");
        assertThat(c.edges).extracting(e -> e.from() + " -> " + e.to()).as("after, lower; a lookup's where").containsExactly(
                "row:sched.step:1 -> job:billing:invoiceJob");
        assertThat(trace).as(String.join("\n", trace)).contains("rule steps: matched")
                .anyMatch(l -> l.contains("lookup job by id ending :exportjob: nothing found"))
                .anyMatch(l -> l.contains("{job._id}") && l.contains("skipped: {job._id} has no value; there are:"));
        List<String> other = new ArrayList<>();
        m.apply(KnowledgeBase.load(List.of(tmp)), new Collect(), "row:sched.step:2", other);
        assertThat(other).singleElement().asString().isEqualTo("rule steps: not matched: where type (in [K8S_JOB, CRON_JOB]): is HTTP");
        assertThat(stats.skippedExample().get("steps")).contains("has no value");

        assertThatThrownBy(() -> FactMapping.parse(new ObjectMapper(new YAMLFactory()).readTree("""
                mappings:
                  - name: bad
                    match: {kind: row}
                    nodes:
                      - {id: "team:{owner | shout}", kind: team}
                """))).hasMessageContaining("unknown filter 'shout'");
    }
}
