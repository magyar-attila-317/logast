package com.example.e2e.job;

import org.platformlib.Week;

/** Built from a library value logast cannot resolve: `var summary = RunSummary.of(...)` must keep its type. */
public record RunSummary(String runCode, int items) {
    public static RunSummary of(String runCode, Week week) {
        return new RunSummary(runCode, week.number());
    }
}
