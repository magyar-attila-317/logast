package dev.logast;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * #321: checks a YAML file against one of logast's schemas, with the draft-07 keywords they use (type, enum, pattern,
 * minimum, required, properties, patternProperties, additionalProperties, propertyNames, items, oneOf): {@code logast schemas
 * --check} and the tests. One line per problem, with its YAML path.
 */
public final class SchemaCheck {
    private SchemaCheck() {
    }

    public static List<String> problems(JsonNode schema, JsonNode value, String at) {
        List<String> out = new ArrayList<>();
        validate(schema, value, at, out);
        return out;
    }

    static void validate(JsonNode s, JsonNode v, String at, List<String> out) {
        if (s.has("oneOf")) {
            for (JsonNode alt : s.get("oneOf")) {
                List<String> tmp = new ArrayList<>();
                validate(alt, v, at, tmp);
                if (tmp.isEmpty()) {
                    return;
                }
            }
            out.add(at + " matches none of oneOf");
            return;
        }
        if (s.has("type") && !typeMatches(s.get("type"), v)) {
            out.add(at + " is " + v.getNodeType() + ", the schema wants " + s.get("type"));
            return;
        }
        if (s.has("enum")) {
            boolean in = false;
            for (JsonNode e : s.get("enum")) {
                in |= e.equals(v) || e.isTextual() && v.isTextual() && e.asText().equals(v.asText());
            }
            if (!in) {
                out.add(at + " " + v + " is not one of " + s.get("enum"));
            }
        }
        if (s.has("pattern") && v.isTextual() && !Pattern.compile(s.get("pattern").asText()).matcher(v.asText()).find()) {
            out.add(at + " " + v + " does not match " + s.get("pattern").asText());
        }
        if (s.has("minimum") && v.isNumber() && v.asDouble() < s.get("minimum").asDouble()) {
            out.add(at + " " + v + " is below " + s.get("minimum"));
        }
        if (v.isArray() && s.has("items")) {
            for (int i = 0; i < v.size(); i++) {
                validate(s.get("items"), v.get(i), at + "[" + i + "]", out);
            }
        }
        if (v.isObject() && s.has("required")) {
            for (JsonNode r : s.get("required")) {
                if (!v.has(r.asText())) {
                    out.add(at + " needs " + r.asText());
                }
            }
        }
        if (v.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = v.fields();
            while (it.hasNext()) {
                var e = it.next();
                String where = at + (at.endsWith(":") ? " " : ".") + e.getKey();
                if (s.has("propertyNames") && s.get("propertyNames").has("enum")) {
                    validate(s.get("propertyNames"), com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.textNode(e.getKey()), where + " (key)", out);
                }
                JsonNode sub = s.path("properties").get(e.getKey());
                if (sub == null && s.has("patternProperties")) {
                    Iterator<Map.Entry<String, JsonNode>> pp = s.get("patternProperties").fields();
                    while (pp.hasNext() && sub == null) {
                        var p = pp.next();
                        if (Pattern.compile(p.getKey()).matcher(e.getKey()).find()) {
                            sub = p.getValue();
                        }
                    }
                }
                if (sub == null && s.has("additionalProperties")) {
                    JsonNode ap = s.get("additionalProperties");
                    if (ap.isBoolean() && !ap.asBoolean()) {
                        out.add(where + " is not a known key");
                        continue;
                    }
                    sub = ap.isObject() ? ap : null;
                }
                if (sub != null) {
                    validate(sub, e.getValue(), where, out);
                }
            }
        }
    }

    private static boolean typeMatches(JsonNode type, JsonNode v) {
        if (type.isArray()) {
            for (JsonNode t : type) {
                if (typeMatches(t, v)) {
                    return true;
                }
            }
            return false;
        }
        return switch (type.asText()) {
            case "object" -> v.isObject();
            case "array" -> v.isArray();
            case "string" -> v.isTextual();
            case "boolean" -> v.isBoolean();
            case "integer" -> v.isIntegralNumber();
            case "number" -> v.isNumber();
            case "null" -> v.isNull();
            default -> true;
        };
    }
}
