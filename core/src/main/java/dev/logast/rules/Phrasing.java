package dev.logast.rules;

import dev.logast.policy.Policy;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lifecycle phrasing ({@code policy.phrasing.events}): recognises "Starting reconciliation", "Failed to charge card",
 * "Error while syncing menu", … and rewrites them to the canonical form ("Reconciliation started", "Charge card
 * failed", "Syncing menu failed"). A leading tag such as {@code KDS_PRINT:} or {@code [PIN-LOGIN]} is kept.
 */
public final class Phrasing {
    /** Leading tag that is kept as is: "KDS_RELOCATION_PRINT: ", "[PIN-LOGIN] ", "auth.device_challenge ". */
    private static final Pattern TAG = Pattern.compile("^((?:\\[[^\\]]{1,40}]\\s*)|(?:[A-Z][A-Z0-9_\\-]{2,40}:\\s*)|(?:[a-z0-9_]+\\.[a-z0-9_.]+\\s+))");

    private record Variant(String event, Pattern pattern) {
    }

    private final Map<String, String> canonical = new LinkedHashMap<>();
    private final Map<String, Pattern> canonicalPattern = new LinkedHashMap<>();
    private final List<Variant> variants = new ArrayList<>();

    public Phrasing(Policy policy) {
        policy.phrasing().events().forEach((event, def) -> {
            if (def.canonical() == null) {
                return;
            }
            canonical.put(event, def.canonical());
            canonicalPattern.put(event, toPattern(def.canonical(), true));
            for (String v : def.variants()) {
                variants.add(new Variant(event, toPattern(v, false)));
            }
        });
    }

    /** "{subject} failed" → regex; {@code loose} also accepts a trailing ", detail" after the event word. */
    private static Pattern toPattern(String template, boolean loose) {
        String[] parts = template.split("\\{subject}", -1);
        StringBuilder re = new StringBuilder("(?i)^");
        for (int i = 0; i < parts.length; i++) {
            re.append(parts[i].isEmpty() ? "" : Pattern.quote(parts[i]).replace(" ", "\\E\\s+\\Q"));
            if (i < parts.length - 1) {
                re.append("(?<subject>.+?)");
            }
        }
        re.append(loose ? "(?:\\s*[,;:—\\-(].*)?\\s*[.!]?$" : "\\s*[.!]?$");
        return Pattern.compile(re.toString().replace("\\Q\\E", ""));
    }

    public Set<String> events() {
        return canonical.keySet();
    }

    public String canonicalOf(String event) {
        return canonical.get(event);
    }

    /** Event an (already canonical or variant) message expresses, or null. */
    public String eventOf(String text) {
        String body = stripTag(text)[1];
        for (var e : canonicalPattern.entrySet()) {
            if (e.getValue().matcher(body).matches()) {
                return e.getKey();
            }
        }
        for (Variant v : variants) {
            if (v.pattern().matcher(body).matches()) {
                return v.event();
            }
        }
        return null;
    }

    /** What the lifecycle message is about: "Order sync" for "Order sync completed" / "Finished order sync"; else null. */
    public String subjectOf(String text) {
        String body = stripTag(text)[1];
        for (Pattern p : canonicalPattern.values()) {
            Matcher m = p.matcher(body);
            if (m.matches()) {
                return m.group("subject").strip();
            }
        }
        for (Variant v : variants) {
            Matcher m = v.pattern().matcher(body);
            if (m.matches()) {
                return m.group("subject").strip();
            }
        }
        return null;
    }

    /** Canonical rewrite of a variant message; empty when it is already canonical or not a lifecycle message. */
    private static final Pattern DETAIL = Pattern.compile("(:\\s|\\s[-–—]\\s|;\\s|\\s\\()");

    public Optional<String> rewrite(String text) {
        String[] tagged = stripTag(text);
        String full = tagged[1];
        // "Completed order {} - all items paid": rewrite the head, keep the detail after the event word
        Matcher dm = DETAIL.matcher(full);
        String body = dm.find() ? full.substring(0, dm.start()) : full;
        String detail = full.substring(body.length());
        for (Pattern p : canonicalPattern.values()) {
            if (p.matcher(body).matches() || p.matcher(full).matches()) {
                return Optional.empty();
            }
        }
        for (Variant v : variants) {
            Matcher m = v.pattern().matcher(body);
            if (m.matches()) {
                String subject = m.group("subject").strip();
                if (subject.isEmpty() || subject.split("\\s+").length > 8) {
                    return Optional.empty();
                }
                String out = canonical.get(v.event()).replace("{subject}", capitalize(subject)) + detail;
                return Optional.of(tagged[0] + (tagged[0].isEmpty() ? capitalize(out) : out));
            }
        }
        return Optional.empty();
    }

    /** True when {@code text} expresses {@code event} in canonical form ("Payment charge failed, will retry"). */
    public boolean matches(String text, String event) {
        Pattern p = canonicalPattern.get(event);
        return p == null || p.matcher(stripTag(text)[1]).matches();
    }

    private static String[] stripTag(String text) {
        Matcher m = TAG.matcher(text);
        return m.find() ? new String[]{m.group(1), text.substring(m.end())} : new String[]{"", text};
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
