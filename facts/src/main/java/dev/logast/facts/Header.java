package dev.logast.facts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * The first line of a facts file (#152): the schema it follows, who wrote it, for which repo and commit.
 *
 * @param schema           {@link FactFormat#SCHEMA}
 * @param extractor        the extractor's id ("java", "helm", "acme-orchestrator")
 * @param extractorVersion its version, so a fact can be traced to the code that produced it
 * @param repo             the repository the facts are about (evidence without a repo means this one)
 * @param commit           the commit they were extracted from (evidence without a commit means this one)
 * @param generatedAt      ISO-8601 instant; left out where output must be reproducible (golden tests)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"schema", "extractor", "extractorVersion", "repo", "commit", "generatedAt"})
public record Header(String schema, String extractor, String extractorVersion, String repo, String commit, String generatedAt) {
    public static Header of(String extractor, String extractorVersion, String repo, String commit) {
        return new Header(FactFormat.SCHEMA, extractor, extractorVersion, repo, commit, null);
    }
}
