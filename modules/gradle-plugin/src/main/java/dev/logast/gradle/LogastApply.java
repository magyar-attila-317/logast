package dev.logast.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.tasks.options.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * Adopting logast in one step (#122), {@code logast apply <project> --auto --write}: applies every fix that needs no
 * message text to the project's sources, with the build's compile classpath; review the result with {@code git diff}.
 * {@code --preview} (or {@code -PlogastDryRun}) previews the diff without changing a file (not {@code --dry-run}: that is
 * Gradle's own flag, which skips every task). Fails on apply's error exits
 * (rejected fixes, no selection) and on a CHECK FAILED result, with apply's output above the failure.
 * <p>
 * {@code --auto} and {@code --write} are passed explicitly, so the task keeps working whichever defaults apply has.
 */
@UntrackedTask(because = "apply changes the sources it reads")
public abstract class LogastApply extends LogastTask {
    @Input
    @Option(option = "preview", description = "Preview the changes (a diff on the console) without writing a file")
    public abstract Property<Boolean> getDryRun();

    @Override
    protected List<String> command() {
        List<String> cmd = new ArrayList<>(List.of("apply", "--auto"));
        cmd.add(getDryRun().get() ? "--dry-run" : "--write");   // apply writes by default (#122): a preview says so
        return cmd;
    }

    @Override
    protected void configure(LogastWork.Params params) {
        params.getApply().set(true);
    }
}
