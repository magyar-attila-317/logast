package dev.logast.gradle;

import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;

import java.util.List;

/** {@code logast scan <project> --update-baseline}: records today's findings; logastCheck then fails only on new ones. */
@UntrackedTask(because = "a scan also reads logast.yaml and the decisions in .logast/")
public abstract class LogastBaseline extends LogastTask {
    @Internal
    public abstract RegularFileProperty getBaseline();

    @Override
    protected List<String> command() {
        return List.of("scan", "--format", "summary", "--update-baseline", "--baseline",
                getBaseline().get().getAsFile().getAbsolutePath());
    }
}
