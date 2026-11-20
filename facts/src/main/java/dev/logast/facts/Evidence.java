package dev.logast.facts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Where a fact comes from (#152): the file and line that prove it. {@code repo} and {@code commit} may be left out when
 * the file's header names them; {@code root} is the folder of the repo an extractor worked on (a Gradle project, a
 * chart, a Terraform unit).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"repo", "root", "file", "line", "commit"})
public record Evidence(String repo, String root, String file, Integer line, String commit) {
    public static Evidence at(String file, Integer line) {
        return new Evidence(null, null, file, line, null);
    }

    public static Evidence at(String root, String file, Integer line) {
        return new Evidence(null, root, file, line, null);
    }
}
