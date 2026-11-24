package dev.logast.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code logast update [version]}: replaces this install with the latest release (or the given one). It runs the
 * install.sh this install was shipped with: that downloads the release zip from GitHub, checks its sha256 and installs
 * it in the same place. One install path for both, nothing to keep in step.
 */
@Command(name = "update", description = "Replace this install with the latest logast release (or the given version), "
        + "downloaded from GitHub and checked against its sha256")
final class UpdateCmd implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "VERSION", description = "A release, e.g. 0.5.2 (default: the latest)")
    String version;

    @Override
    public Integer call() throws Exception {
        Path home = home();
        Path script = home == null ? null : home.resolve("install.sh");
        if (script == null || !Files.isRegularFile(script)) {
            System.err.println("This install has no install.sh to update with (installed before 0.5.2, or not from a release)."
                    + " Install once with: curl -fsSL https://raw.githubusercontent.com/magyar-attila-317/logast/main/install.sh | bash");
            return 2;
        }
        List<String> cmd = new ArrayList<>(List.of("bash", script.toString()));
        if (version == null || version.isBlank()) {
            cmd.add("--latest");
        } else {
            cmd.addAll(List.of("--version", version.strip()));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        // the same place: an install under LOGAST_HOME stays there
        pb.environment().put("LOGAST_HOME", home.toString());
        return pb.start().waitFor();
    }

    /** The install's folder: the parent of the lib/ folder this class's jar is in; null when not run from a jar. */
    static Path home() {
        try {
            Path jar = Path.of(UpdateCmd.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path lib = jar.getParent();
            return lib != null && lib.getFileName() != null && lib.getFileName().toString().equals("lib") ? lib.getParent() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
