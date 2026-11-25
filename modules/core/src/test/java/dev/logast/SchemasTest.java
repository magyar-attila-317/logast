package dev.logast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #321: the schemas say what logast reads. The policy schema has exactly the keys the policy records bind (Jackson
 * rejects any other), its defaults are the built-in ones, and every YAML file the e2e scenarios hold validates against
 * its schema.
 */
class SchemasTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    /** Components the loader fills itself; not settings. */
    private static final Set<String> INTERNAL = Set.of("overrides", "warnings", "resilience.chosen");
    /** Keys the loader takes out before binding (#124, #236) and the merge switch on any object. */
    private static final Set<String> LOADER = Set.of("fleet", "rollout", "override");

    @Test
    void thePolicySchemaHasExactlyThePolicysKeys() throws Exception {
        JsonNode schema = JSON.readTree(Schemas.text(Schemas.POLICY));
        Set<String> fromRecords = new TreeSet<>();
        paths(Policy.class, "", fromRecords);
        Set<String> fromSchema = new TreeSet<>();
        paths(schema, "", fromSchema);
        Set<String> onlySchema = new TreeSet<>(fromSchema);
        onlySchema.removeAll(fromRecords);
        Set<String> onlyRecords = new TreeSet<>(fromRecords);
        onlyRecords.removeAll(fromSchema);
        assertThat(onlySchema).as("in the schema, not in the policy records").isEmpty();
        assertThat(onlyRecords).as("in the policy records, not in the schema").isEmpty();
    }

    @Test
    void theSchemaDefaultsAreTheBuiltInOnes(@TempDir Path empty) throws Exception {
        JsonNode resolved = JSON.valueToTree(Policy.resolve(empty, null));
        JsonNode schema = JSON.readTree(Schemas.text(Schemas.POLICY));
        List<String> wrong = new ArrayList<>();
        defaults(schema, resolved, "", wrong);
        assertThat(wrong).as("schema default ≠ built-in value").isEmpty();
    }

    @Test
    void theRuleIdsAndPacksComeFromTheCode() throws Exception {
        JsonNode schema = JSON.readTree(Schemas.text(Schemas.POLICY));
        List<String> rules = new ArrayList<>();
        schema.at("/properties/rules/propertyNames/enum").forEach(n -> rules.add(n.asText()));
        assertThat(rules).containsExactlyElementsOf(dev.logast.rules.Rules.all());
        List<String> packs = new ArrayList<>();
        schema.at("/properties/packs/items/enum").forEach(n -> packs.add(n.asText()));
        assertThat(packs).containsExactlyElementsOf(dev.logast.rules.Rules.packs());
    }

    @Test
    void theReferenceListsEverySettingWithItsDefault() {
        String ref = Schemas.reference();
        assertThat(ref).contains("#   keyStyle: camel   # How MDC and [k=v] keys are spelled")
                .contains("#   extractRepeated: 2   #")
                .contains("# packs: [logging, tracing, metrics, resilience, errors, audit]   #")
                .contains("#   httpReadTimeout: 30s   #")
                .doesNotContain("override:").doesNotContain("noInfoInLoops").doesNotContain("# glossary:");
        assertThat(ref.lines()).allMatch(l -> l.startsWith("#"));
    }

    @Test
    void aStampedFileNamesItsSchemaForBothIdes(@TempDir Path root) throws Exception {
        Path messages = root.resolve(".logast/decisions/messages.yaml");
        String once = Schemas.stamp(messages, "# Optional.\nSUG-1: {}\n");
        assertThat(once).startsWith("# $schema: ../schemas/messages.schema.json\n"
                + "# yaml-language-server: $schema=../schemas/messages.schema.json\n# Optional.\n");
        assertThat(Schemas.stamp(messages, once)).as("stamping twice changes nothing").isEqualTo(once);
        assertThat(root.resolve(".logast/schemas/messages.schema.json")).exists();
        assertThat(Schemas.stamp(root.resolve("logast.yaml"), "packs: [logging]\n"))
                .startsWith("# $schema: .logast/schemas/logast.schema.json\n");
        assertThat(Schemas.stamp(root.resolve("fleet/company.yaml"), "packs: [logging]\n"))
                .startsWith("# $schema: schemas/logast.schema.json\n");
        assertThat(root.resolve("fleet/schemas/logast.schema.json")).exists();
    }

    /** Every logast.yaml / decision file the e2e fixtures and snapshots hold is valid against its schema. */
    @Test
    void theE2eFilesValidate() throws Exception {
        Path e2e = Fixtures.root().resolve("../e2e").normalize();
        if (!Files.isDirectory(e2e)) {
            e2e = Path.of(System.getProperty("user.dir")).resolve("../../testing/e2e").normalize();
        }
        List<String> problems = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(e2e)) {
            for (Path f : files.filter(p -> Schemas.FOR.containsKey(p.getFileName().toString())).sorted().toList()) {
                JsonNode doc = YAML.readTree(f.toFile());
                if (doc == null || doc.isMissingNode()) {
                    continue;
                }
                JsonNode schema = JSON.readTree(Schemas.text(Schemas.FOR.get(f.getFileName().toString())));
                problems.addAll(SchemaCheck.problems(schema, doc, e2e.relativize(f) + ":"));
                checked++;
            }
        }
        assertThat(checked).as("files checked").isGreaterThan(50);
        assertThat(problems).isEmpty();
    }

    // ---- the policy records' paths -----------------------------------------------------------------------------

    private static void paths(Class<?> record, String prefix, Set<String> out) {
        for (RecordComponent c : record.getRecordComponents()) {
            String name = c.getName().equals("parents") ? "extends" : c.getName();   // @JsonProperty("extends")
            String path = prefix + name;
            if (INTERNAL.contains(path) || name.equals("override")) {
                continue;   // override: the loader's merge switch, in the schema on every object
            }
            out.add(path);
            component(c.getGenericType(), path, out);
        }
    }

    private static void component(Type t, String path, Set<String> out) {
        if (t instanceof Class<?> c && c.isRecord()) {
            paths(c, path + ".", out);
        } else if (t instanceof ParameterizedType p) {
            Type[] args = p.getActualTypeArguments();
            if (p.getRawType() == List.class) {
                component(args[0], path + "[]", out);
            } else if (p.getRawType() == Map.class) {
                component(args[1], path + "{}", out);
            }
        }
    }

    private static void paths(JsonNode schema, String prefix, Set<String> out) {
        if (schema.has("properties")) {
            Iterator<Map.Entry<String, JsonNode>> it = schema.get("properties").fields();
            while (it.hasNext()) {
                var e = it.next();
                if (LOADER.contains(e.getKey())) {
                    continue;
                }
                String path = prefix + e.getKey();
                out.add(path);
                sub(e.getValue(), path, out);
            }
        }
    }

    private static void sub(JsonNode p, String path, Set<String> out) {
        if (p.has("properties")) {
            paths(p, path + ".", out);
        } else if (p.path("items").has("properties")) {
            paths(p.get("items"), path + "[].", out);
        } else if (p.path("additionalProperties").has("properties")) {
            paths(p.get("additionalProperties"), path + "{}.", out);
        }
    }

    // ---- defaults ------------------------------------------------------------------------------------------------

    private static void defaults(JsonNode schema, JsonNode value, String prefix, List<String> wrong) {
        if (!schema.has("properties")) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = schema.get("properties").fields();
        while (it.hasNext()) {
            var e = it.next();
            String key = e.getKey().equals("extends") ? "parents" : e.getKey();
            JsonNode actual = value == null ? null : value.get(key);
            JsonNode p = e.getValue();
            if (p.has("default")) {
                JsonNode d = p.get("default");
                boolean same = actual != null && (d.isNumber() && actual.isNumber() ? d.asDouble() == actual.asDouble() : d.equals(actual));
                if (!same) {
                    wrong.add(prefix + e.getKey() + ": schema " + d + ", built in " + actual);
                }
            }
            if (p.has("properties") && actual != null) {
                defaults(p, actual, prefix + e.getKey() + ".", wrong);
            }
        }
    }

}
