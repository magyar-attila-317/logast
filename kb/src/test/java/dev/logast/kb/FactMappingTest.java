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
            {"node":{"id":"row:orchestration.job_definition:2","kind":"row","env":"prod","attrs":{"id":2,"name":"settlement","service":"billing","profile":"calculation","cron":"30 5 * * *","enabled":true},"evidence":{"file":"db/migration/common/V1__jobs.sql","line":8}}}
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
                "job:billing:settlement -runs-> profile:billing:calculation [prod]",
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
}
