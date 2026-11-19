package dev.logast.gradle;

import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.options.Option;

import java.util.List;

/** {@code logast scan <project> --format <format>}: writes .logast/reports/orders.json and the decision files. */
@UntrackedTask(because = "a scan also reads logast.yaml and the decisions in .logast/")
public abstract class LogastScan extends LogastTask {
    @Input
    @Option(option = "format", description = "summary | human | compact | json | sarif")
    public abstract Property<String> getFormat();

    @Override
    protected List<String> command() {
        return List.of("scan", "--format", getFormat().get());
    }
}
