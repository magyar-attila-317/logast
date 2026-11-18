package dev.logast.apply;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import dev.logast.orders.OrderJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The YAML of the files a person edits in {@code .logast/decisions/} (#108): plain scalars where they read back the same,
 * quotes where YAML would read something else (numbers, true/false, ": "), no folded lines, so a diff shows one changed
 * value as one changed line. The explanation of a file is a comment block at its top.
 */
public final class DecisionYaml {
    public static final ObjectMapper MAPPER = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .build());
    private static final int WIDTH = 116;

    private DecisionYaml() {
    }

    /** The header comment: each paragraph wrapped (a line break in it starts a new line), "# " before every line, "#" between them. */
    public static String comment(String... paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraphs.length; i++) {
            if (i > 0) {
                sb.append("#\n");
            }
            for (String part : paragraphs[i].strip().split("\n")) {   // a line break in a paragraph starts a new line
                StringBuilder line = new StringBuilder("#");
                for (String word : part.strip().split("\\s+")) {
                    if (line.length() > 1 && line.length() + 1 + word.length() > WIDTH) {
                        sb.append(line).append('\n');
                        line = new StringBuilder("#");
                    }
                    line.append(' ').append(word);
                }
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    /** {@code header} followed by {@code body} as YAML. */
    public static String text(String header, JsonNode body) {
        try {
            return header + MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Writes the file (and its folder) unless it already reads exactly so; returns whether it was written. */
    public static boolean write(Path file, String header, JsonNode body) {
        String text = text(header, body);
        try {
            if (Files.isRegularFile(file) && Files.readString(file, StandardCharsets.UTF_8).equals(text)) {
                return false;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The comment lines at the top of the file (the header logast wrote), or "" when there is no file. */
    public static String header(Path file) {
        if (!Files.isRegularFile(file)) {
            return "";
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!l.startsWith("#")) {
                    break;
                }
                sb.append(l).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The file as a tree: YAML, or JSON when its name ends in .json (a {@code --fills} file of the earlier format). An
     * empty file is an empty object.
     */
    public static ObjectNode read(Path file) throws IOException {
        boolean json = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
        JsonNode n = (json ? OrderJson.MAPPER : MAPPER).readTree(file.toFile());
        return n instanceof ObjectNode o ? o : MAPPER.createObjectNode();
    }

    /** Top-level keys starting with "_" (the earlier JSON files' "_readme" and "_status") are the header now: left out. */
    public static ObjectNode withoutNotes(ObjectNode n) {
        ObjectNode out = n.deepCopy();
        List<String> notes = new ArrayList<>();
        out.fieldNames().forEachRemaining(k -> {
            if (k.startsWith("_")) {
                notes.add(k);
            }
        });
        out.remove(notes);
        return out;
    }
}
