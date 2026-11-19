package dev.logast.gradle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.logast.cli.Main;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Runs the logast CLI in the worker process, as {@code logast <args>} would, and turns its exit code into a result. */
public abstract class LogastWork implements WorkAction<LogastWork.Params> {
    public interface Params extends WorkParameters {
        ListProperty<String> getArgs();

        /** The gate (logastCheck): stdout is the JSON list of new findings, exit 2 fails the build naming them. */
        Property<Boolean> getGate();

        Property<String> getFailOn();

        Property<String> getBaselinePath();

        /** Where logast's standard output goes instead of the console (logastSarif's report), if set. */
        RegularFileProperty getStdoutFile();
    }

    @Override
    public void execute() {
        String[] args = getParameters().getArgs().get().toArray(String[]::new);
        if (!getParameters().getGate().getOrElse(false)) {
            int rc;
            if (getParameters().getStdoutFile().isPresent()) {
                File file = getParameters().getStdoutFile().get().getAsFile();
                file.getParentFile().mkdirs();
                try (PrintStream out = new PrintStream(new FileOutputStream(file), true, StandardCharsets.UTF_8)) {
                    rc = run(args, out);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                System.out.println("logast: " + file);
            } else {
                rc = new CommandLine(new Main()).execute(args);
            }
            if (rc != 0) {
                throw new GradleException("logast " + String.join(" ", args) + " failed (exit " + rc + ")");
            }
            return;
        }
        ByteArrayOutputStream json = new ByteArrayOutputStream();
        int rc = run(args, new PrintStream(json, true, StandardCharsets.UTF_8));
        String failOn = getParameters().getFailOn().get();
        String baseline = getParameters().getBaselinePath().get();
        switch (rc) {
            case 0 -> System.out.println("logast: no new findings at or above " + failOn + " (baseline " + baseline + ")");
            case 2 -> throw new GradleException(newFindings(json.toString(StandardCharsets.UTF_8), failOn, baseline));
            case 3 -> throw new GradleException("logast: no baseline at " + baseline
                    + ": record it with `./gradlew logastBaseline` and commit it");
            case 4 -> throw new GradleException("logast: the analysis is incomplete (see the warnings above), so the check "
                    + "cannot pass: a broken analysis finds less");
            default -> throw new GradleException("logast " + String.join(" ", args) + " failed (exit " + rc + ")");
        }
    }

    /** Runs the CLI with its standard output going to {@code out}. */
    private static int run(String[] args, PrintStream out) {
        PrintStream stdout = System.out;
        System.setOut(out);
        try {
            return new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(stdout);
            out.flush();
        }
    }

    /** The failure message: one line per new finding, as `logast scan --format compact` heads it. */
    static String newFindings(String json, String failOn, String baseline) {
        List<Map<String, Object>> orders;
        try {
            orders = new ObjectMapper().readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return "logast: new findings at or above " + failOn + " (could not read them: " + e.getMessage() + ")";
        }
        String lines = orders.stream()
                .map(o -> "  " + o.get("id") + "  " + o.get("severity") + "  " + o.get("rule") + "  " + o.get("file")
                        + ":" + o.get("line") + (o.get("method") == null ? "" : "  " + o.get("method"))
                        + (o.get("title") == null ? "" : "\n      " + o.get("title")))
                .collect(Collectors.joining("\n"));
        return "logast: " + orders.size() + " new finding" + (orders.size() == 1 ? "" : "s") + " at or above " + failOn
                + " (not in the baseline " + baseline + "):\n" + lines
                + "\nFix them (`logast scan .` shows each in full), or record them as known with `./gradlew logastBaseline`.";
    }
}
