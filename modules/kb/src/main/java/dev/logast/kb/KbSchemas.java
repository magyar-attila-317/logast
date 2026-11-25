package dev.logast.kb;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * #321: the knowledge files people edit (knowledge.yml, knowledge.proposed.yml, knowledge.decisions.yml) name their
 * JSON schema on top, for IntelliJ ({@code # $schema:}) and VS Code ({@code # yaml-language-server:}); the schema is
 * copied into {@code schemas/} beside them. The same convention as dev.logast.Schemas, which the kb does not depend on.
 */
public final class KbSchemas {
    public static final String KNOWLEDGE = "knowledge.schema.json";
    public static final String MAPPING = "mapping.schema.json";
    private static final String INTELLIJ = "# $schema: ";
    private static final String VSCODE = "# yaml-language-server: $schema=";

    private KbSchemas() {
    }

    /** {@code text} with the knowledge schema's two lines first (earlier ones replaced); the schema copied beside the file. */
    public static String stamp(Path file, String text) throws IOException {
        Path dir = file.toAbsolutePath().normalize().getParent().resolve("schemas");
        Files.createDirectories(dir);
        byte[] schema;
        try (InputStream in = KbSchemas.class.getResourceAsStream("/schemas/" + KNOWLEDGE)) {
            schema = Objects.requireNonNull(in, KNOWLEDGE).readAllBytes();
        }
        Path target = dir.resolve(KNOWLEDGE);
        if (!Files.isRegularFile(target) || !java.util.Arrays.equals(Files.readAllBytes(target), schema)) {
            Files.write(target, schema);
        }
        String rest = text;
        while (rest.startsWith(INTELLIJ) || rest.startsWith(VSCODE)) {
            int nl = rest.indexOf('\n');
            rest = nl < 0 ? "" : rest.substring(nl + 1);
        }
        String rel = "schemas/" + KNOWLEDGE;
        return INTELLIJ + rel + "\n" + VSCODE + rel + "\n" + rest;
    }

    public static String text(String name) throws IOException {
        try (InputStream in = KbSchemas.class.getResourceAsStream("/schemas/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
