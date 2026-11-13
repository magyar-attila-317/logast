package dev.logast.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code logging.keyStyle}: how MDC keys and {@code [k=v]} keys are spelled. */
public enum KeyStyle {
    DOTTED, CAMEL, PASCAL, SNAKE, KEEP;

    public static KeyStyle parse(String s) {
        if (s == null) {
            return CAMEL;   // orderId: Java naming, and no "order" object vs "order.id" field conflict in Elasticsearch
        }
        return switch (s.toLowerCase(Locale.ROOT).replaceAll("[\\s_.\\-]", "")) {
            case "dotted", "dot", "dotcase" -> DOTTED;
            case "camel", "camelcase", "lowercamel", "lowercamelcase" -> CAMEL;
            case "pascal", "pascalcase", "uppercamel", "uppercamelcase" -> PASCAL;
            case "snake", "snakecase" -> SNAKE;
            case "keep", "none", "asis" -> KEEP;
            default -> throw new IllegalArgumentException("logging.keyStyle '" + s
                    + "': use dotted (order.id), camel (orderId), pascal (OrderId), snake (order_id) or keep");
        };
    }

    /** {@code orderId, order_id, order.id, OrderId} → this style's spelling of the same words. */
    public String apply(String key) {
        if (this == KEEP || key == null || key.isEmpty()) {
            return key;
        }
        List<String> words = words(key);
        if (words.isEmpty()) {
            return key;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            switch (this) {
                case DOTTED -> sb.append(i > 0 ? "." : "").append(w);
                case SNAKE -> sb.append(i > 0 ? "_" : "").append(w);
                case CAMEL -> sb.append(i == 0 ? w : cap(w));
                default -> sb.append(cap(w));
            }
        }
        return sb.toString();
    }

    /** Lower-case words: camel humps, digits stay with their word, any non-alphanumeric separates; "ID"/"UUID" are words. */
    static List<String> words(String key) {
        List<String> out = new ArrayList<>();
        for (String part : key.split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }
            for (String w : part.split("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")) {
                out.add(w.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static String cap(String w) {
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
