package dev.logast.export;

import dev.logast.facts.FactSink;

import java.nio.file.Path;

/**
 * Something that turns one root of a repo (a Gradle project, a chart, a Terraform unit) into facts (#153,
 * docs/facts.md). Extractors never see each other: links across roots and repos are made by whoever loads the facts,
 * through ids. Finding the roots of a repo (marker files) comes with the extractors that need it.
 */
public interface Extractor {
    /** The extractor's id, written into the facts file's header ("java", "helm", …). */
    String id();

    void extract(Path root, FactSink out);
}
