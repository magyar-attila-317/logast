package dev.logast.gradle;

import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;

import java.util.List;

/**
 * {@code logast scan <project> --format sarif > <file>}: every finding as SARIF 2.1.0, for GitHub code scanning
 * ({@code github/codeql-action/upload-sarif}) to show as PR annotations. Never fails on findings; the gate is logastCheck.
 */
@UntrackedTask(because = "a scan also reads logast.yaml and the decisions in .logast/")
public abstract class LogastSarif extends LogastTask {
    /** Default {@code build/reports/logast/logast.sarif}. */
    @OutputFile
    public abstract RegularFileProperty getSarifFile();

    @Override
    protected List<String> command() {
        return List.of("scan", "--format", "sarif");
    }

    @Override
    protected void configure(LogastWork.Params params) {
        params.getStdoutFile().set(getSarifFile());
    }
}
