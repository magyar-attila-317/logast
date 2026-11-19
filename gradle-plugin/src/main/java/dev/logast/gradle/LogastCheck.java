package dev.logast.gradle;

import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.options.Option;

import java.util.List;

/**
 * The CI gate, {@code logast scan <project> --baseline <file> --fail-on <severity>}: fails the build on a finding that
 * is not in the baseline and has the severity {@code failOn} or higher, and on an incomplete analysis (a broken
 * analysis finds less and must not pass). The failure names every new finding.
 */
@UntrackedTask(because = "a scan also reads logast.yaml and the decisions in .logast/")
public abstract class LogastCheck extends LogastTask {
    static final List<String> SEVERITIES = List.of("low", "medium", "high");

    @Internal
    public abstract RegularFileProperty getBaseline();

    @Input
    @Option(option = "fail-on", description = "low | medium | high")
    public abstract Property<String> getFailOn();

    @Override
    protected List<String> command() {
        String failOn = getFailOn().get();
        if (!SEVERITIES.contains(failOn)) {
            throw new InvalidUserDataException("logast.failOn = \"" + failOn + "\": use one of " + SEVERITIES);
        }
        // json: the new findings at or above failOn, which the worker turns into the failure message
        return List.of("scan", "--format", "json", "--severity", failOn, "--fail-on", failOn,
                "--baseline", getBaseline().get().getAsFile().getAbsolutePath());
    }

    @Override
    protected void configure(LogastWork.Params params) {
        params.getGate().set(true);
        params.getFailOn().set(getFailOn());
        params.getBaselinePath().set(getBaseline().get().getAsFile().getAbsolutePath());
    }
}
