package dev.logast.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * #321: {@code logast schemas [dir]}: the JSON schemas of every file a person edits, for the IDE. apply and the fleet
 * and kb commands copy them beside the files they write themselves; this writes all of them, for files logast never
 * writes (a fact mapping, a knowledge.yml started by hand), and says which line names a schema in a file.
 */
@Command(name = "schemas", description = "Write the JSON schemas of every file you edit by hand (logast.yaml, decisions, "
        + "fleet and kb files) for the IDE, and show the line that names one in a file")
final class SchemasCmd implements Callable<Integer> {
    @Parameters(index = "0", arity = "0..1", paramLabel = "DIR", description = "Where to write them (default: .logast/schemas)")
    Path dir;
    @picocli.CommandLine.Option(names = "--check", paramLabel = "FILE", description = "Check these files against their schemas "
            + "instead (by name: logast.yaml, messages.yaml, knowledge.yml, …; others with --schema); exit 1 on a problem")
    java.util.List<Path> check = new java.util.ArrayList<>();
    @picocli.CommandLine.Option(names = "--schema", paramLabel = "NAME", description = "The schema for --check files whose name "
            + "says nothing, e.g. mapping.schema.json, config.schema.json")
    String schema;

    @Override
    public Integer call() throws IOException {
        if (!check.isEmpty()) {
            return check();
        }
        Path out = (dir != null ? dir : Path.of(dev.logast.Schemas.DIR)).toAbsolutePath().normalize();
        Files.createDirectories(out);
        dev.logast.Schemas.writeAll(out);
        for (String kb : new String[]{dev.logast.kb.KbSchemas.KNOWLEDGE, dev.logast.kb.KbSchemas.MAPPING}) {
            Files.writeString(out.resolve(kb), dev.logast.kb.KbSchemas.text(kb), StandardCharsets.UTF_8);
        }
        System.out.println("schemas in " + out + ":");
        for (Map.Entry<String, String> e : dev.logast.Schemas.FILES.entrySet()) {
            System.out.printf("  %-34s %s%n", e.getKey(), e.getValue());
        }
        System.out.println();
        System.out.println("A file names its schema on its first lines, relative to the file (IntelliJ reads the first, VS Code the second):");
        System.out.print(dev.logast.Schemas.modelines("<path to>/mapping.schema.json").replaceAll("(?m)^", "  "));
        System.out.println("logast writes these lines itself into the files it writes. Check a file: logast schemas --check <file>");
        return 0;
    }

    private int check() throws IOException {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var yaml = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        int bad = 0;
        for (Path f : check) {
            String name = f.getFileName().toString();
            String s = schema != null ? schema : dev.logast.Schemas.forFile(name) != null ? dev.logast.Schemas.forFile(name)
                    : name.startsWith("knowledge.") ? dev.logast.kb.KbSchemas.KNOWLEDGE : null;
            if (s == null) {
                System.err.println(f + ": no schema known for this name; say which with --schema");
                return 2;
            }
            String text = dev.logast.Schemas.available(s) ? dev.logast.Schemas.text(s) : dev.logast.kb.KbSchemas.text(s);
            java.util.List<String> problems;
            try {
                com.fasterxml.jackson.databind.JsonNode doc = yaml.readTree(f.toFile());
                problems = doc == null ? java.util.List.of() : dev.logast.SchemaCheck.problems(json.readTree(text), doc, f + ":");
            } catch (IOException e) {
                problems = java.util.List.of(f + ": not YAML: " + e.getMessage());
            }
            if (problems.isEmpty()) {
                System.out.println(f + ": ok (" + s + ")");
            } else {
                bad++;
                problems.forEach(System.out::println);
            }
        }
        return bad == 0 ? 0 : 1;
    }
}
