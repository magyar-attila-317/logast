package dev.logast.rules;

import dev.logast.model.Entrypoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * #231: wording logast writes when nobody wrote a text, made from the code's own names. It is marked
 * {@code // logast:wording}, so MESSAGE_SUGGESTED asks for domain words; a text in messages.yaml always wins.
 */
final class Wording {
    /** Path words that say what a request does to the resource before them. */
    private static final Map<String, String> ACTIONS = Map.ofEntries(
            Map.entry("new", "creation"), Map.entry("create", "creation"), Map.entry("add", "creation"),
            Map.entry("edit", "update"), Map.entry("update", "update"),
            Map.entry("delete", "deletion"), Map.entry("remove", "deletion"));
    private static final Map<String, String> VERBS = Map.of("POST", "creation", "PUT", "update", "PATCH", "update",
            "DELETE", "deletion");

    private Wording() {
    }

    /**
     * What an entrypoint starts: {@code POST /owners/{ownerId}/pets/new} → "Pet creation", {@code PUT /orders/{id}} →
     * "Order update", {@code POST /orders/{id}/cancel} → "Order cancel"; other kinds: the method's words
     * ({@code processPayment} → "Process payment"). Null when there are no words to use.
     */
    static String entrySubject(Entrypoint ep) {
        if (ep.kind() == Entrypoint.Kind.HTTP) {
            String[] parts = ep.label().split(" ", 2);
            if (parts.length == 2) {
                List<String> words = new ArrayList<>();
                for (String s : parts[1].split("/")) {
                    if (!s.isBlank() && !s.startsWith("{") && s.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                        words.add(s.toLowerCase(Locale.ROOT));
                    }
                }
                String last = parts[1].replaceAll("/+$", "");
                boolean endsWithVariable = last.endsWith("}");
                if (!words.isEmpty()) {
                    String tail = words.getLast();
                    if (!endsWithVariable && ACTIONS.containsKey(tail) && words.size() > 1) {
                        return capital(singular(words.get(words.size() - 2)) + " " + ACTIONS.get(tail));
                    }
                    if (endsWithVariable || words.size() == 1 || !VERBS.containsKey(parts[0])) {
                        String action = VERBS.get(parts[0]);
                        return action == null ? null : capital(singular(tail) + " " + action);
                    }
                    // an action of its own after a resource: /orders/{id}/cancel
                    return capital(singular(words.get(words.size() - 2)) + " " + words(tail));
                }
            }
            return null;
        }
        // a listener's onReturn / handleRefund: the event is what follows the prefix
        String name = ep.method().name().replaceFirst("^(on|handle)(?=[A-Z])", "");
        return capital(words(name));
    }

    /** {@code "Pet creation"} in the policy's form of the event ("{subject} started"). */
    static String event(RuleContext ctx, String subject, String event) {
        String pattern = ctx.phrasing.canonicalOf(event);
        return capital(pattern == null ? subject + " " + event : pattern.replace("{subject}", subject));
    }

    /** {@code orderItems} / {@code order-items} → "order items". */
    static String words(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[-_]+", " ").toLowerCase(Locale.ROOT).strip();
    }

    static String singular(String word) {
        String w = words(word);
        if (w.endsWith("ies") && w.length() > 4) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("sses") || w.endsWith("xes") || w.endsWith("ches") || w.endsWith("shes")) {
            return w.substring(0, w.length() - 2);
        }
        return w.endsWith("s") && !w.endsWith("ss") && w.length() > 3 ? w.substring(0, w.length() - 1) : w;
    }

    static String capital(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
