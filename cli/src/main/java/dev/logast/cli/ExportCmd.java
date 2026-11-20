package dev.logast.cli;

import dev.logast.export.Export;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Callable;

/** #153: a service's facts (docs/facts.md) for a knowledge base across repos. */
@Command(name = "export", hidden = true, description = "Write the service's facts (entrypoints, calls, topics, profiles, "
        + "config keys, jobs, libraries) as a facts file (docs/facts.md); read-only")
final class ExportCmd extends Main.Common implements Callable<Integer> {
    @Option(names = "--out", description = "The facts file to write (default: standard output)")
    Path out;
    @Option(names = "--repo", description = "The repository name the facts are about (default: the folder's name)")
    String repo;

    @Override
    public Integer call() throws IOException {
        Path root = root();
        if (out == null) {
            Writer w = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            Export.java(root, classpath(), policy(), repo, version(), w);
            w.flush();
        } else {
            if (out.toAbsolutePath().getParent() != null) {
                Files.createDirectories(out.toAbsolutePath().getParent());
            }
            try (Writer w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                Export.java(root, classpath(), policy(), repo, version(), w);
            }
            System.err.println("facts: " + out);
        }
        return 0;
    }

    private static String version() throws IOException {
        Properties p = new Properties();
        try (var in = ExportCmd.class.getResourceAsStream("/logast-version.properties")) {
            if (in != null) {
                p.load(in);
            }
        }
        return p.getProperty("version", "dev");
    }
}
