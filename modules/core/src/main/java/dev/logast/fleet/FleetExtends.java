package dev.logast.fleet;

import dev.logast.CentralVocabulary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #133: the fleet reference in {@code extends:}. {@code fleet:<name>} names a fleet's company.yaml by the fleet's name;
 * each machine resolves it ({@link FleetHome}). {@code logast fleet join} writes it as the first entry, with the path it
 * resolves to here as a comment, {@code fleet leave} takes it out; both edit lines only, so the rest of the file and its
 * comments stay.
 */
public final class FleetExtends {
    /** The prefix of a fleet reference in {@code extends:}. */
    public static final String PREFIX = "fleet:";
    private static final Pattern EXTENDS = Pattern.compile("extends\\s*:(.*)");
    private static final Pattern ITEM = Pattern.compile("(\\s*)-\\s+(.*?)(\\s+#.*)?");
    private static final Pattern VALUE = Pattern.compile("(.*?)(\\s+#.*)?");

    private FleetExtends() {
    }

    /** The fleet an {@code extends:} entry names ({@code fleet:<name>}, {@code fleet:<name>@policy-N}); null for any other entry. */
    public static String name(String entry) {
        String e = entry == null ? "" : entry.strip();
        if (!e.startsWith(PREFIX)) {
            return null;
        }
        String name = e.substring(PREFIX.length()).strip();
        int at = name.indexOf('@');
        name = at < 0 ? name : name.substring(0, at).strip();
        return name.isEmpty() ? null : name;
    }

    /** #236: the policy version an entry pins ({@code fleet:<name>@policy-N} → {@code policy-N}); null when unpinned. */
    public static String pin(String entry) {
        if (name(entry) == null) {
            return null;
        }
        String e = entry.strip();
        int at = e.indexOf('@');
        String pin = at < 0 ? "" : e.substring(at + 1).strip();
        return pin.isEmpty() ? null : pin;
    }

    /** Where {@code fleet:<name>} points on this machine: the fleet folder's company.yaml. */
    public static Path company(String name) {
        return FleetHome.fleetDir(FleetHome.Env.current(), name).dir().resolve(CentralVocabulary.COMPANY).toAbsolutePath().normalize();
    }

    /** #236: {@code fleet:<name>@<pin>}: the company.yaml of that policy version (from git at the tag); unpinned: {@link #company(String)}. */
    public static Path company(String name, String pin) {
        if (pin == null) {
            return company(name);
        }
        Path dir = FleetHome.fleetDir(FleetHome.Env.current(), name).dir();
        return PolicyVersions.company(dir, pin).toAbsolutePath().normalize();
    }

    /**
     * #236: {@code fleet:<name>} pinned to {@code tag} in {@code extends:}: the entry is rewritten (or added first), the
     * rest of the file stays.
     */
    public static List<String> pinTo(List<String> lines, String name, String tag) {
        List<String> out = add(lines, name, company(name));
        Extends e = find(out);
        int i = e == null ? -1 : e.indexOf(name);
        if (i < 0) {
            return out;
        }
        String entry = PREFIX + name + "@" + tag;
        if (e.inline()) {
            List<String> values = new ArrayList<>(e.values());
            values.set(i, entry);
            out.set(e.at(), "extends: " + (e.value().startsWith("[") || values.size() > 1 ? "[" + String.join(", ", values) + "]"
                    : values.getFirst()) + e.comment());
            return out;
        }
        Matcher m = ITEM.matcher(out.get(e.items().get(i)));
        String indent = m.matches() ? m.group(1) : "  ";
        out.set(e.items().get(i), indent + "- " + entry + "    " + comment(company(name, tag)));
        return out;
    }

    /** The list item for the fleet: {@code <indent>- fleet:<name>    # = <company> on this machine}. */
    public static String item(String indent, String name, Path company) {
        return indent + "- " + PREFIX + name + "    " + comment(company);
    }

    private static String comment(Path company) {
        return "# = " + company.toAbsolutePath().normalize() + " on this machine";
    }

    /** The {@code extends:} of a file, as lines. */
    private record Extends(int at, String value, String comment, List<Integer> items, List<String> values) {
        boolean inline() {
            return !value.isEmpty();
        }

        int indexOf(String name) {
            for (int i = 0; i < values.size(); i++) {
                if (name.equals(FleetExtends.name(values.get(i)))) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static Extends find(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = EXTENDS.matcher(lines.get(i));
            if (!m.matches()) {
                continue;
            }
            Matcher v = VALUE.matcher(m.group(1));   // " # note" is a comment, not a value
            v.matches();
            String value = v.group(1).strip();
            String comment = v.group(2) == null ? "" : v.group(2);
            List<Integer> items = new ArrayList<>();
            List<String> values = new ArrayList<>();
            if (!value.isEmpty()) {
                String inner = value.startsWith("[") && value.endsWith("]") ? value.substring(1, value.length() - 1) : value;
                for (String s : value.startsWith("[") ? inner.split(",") : new String[]{inner}) {
                    if (!s.isBlank()) {
                        values.add(unquote(s.strip()));
                    }
                }
                return new Extends(i, value, comment, items, values);
            }
            for (int j = i + 1; j < lines.size(); j++) {
                String l = lines.get(j);
                if (l.isBlank() || l.stripLeading().startsWith("#")) {
                    continue;
                }
                Matcher it = ITEM.matcher(l);
                if (it.matches()) {
                    items.add(j);
                    values.add(unquote(it.group(2).strip()));
                } else if (!l.startsWith(" ")) {
                    break;   // the next top-level key
                }
            }
            return new Extends(i, value, comment, items, values);
        }
        return null;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))
                ? s.substring(1, s.length() - 1) : s;
    }

    /**
     * Puts {@code fleet:<name>} first in {@code extends:} (a scalar or flow list becomes a block list; no {@code extends:}:
     * one under the {@code fleet:} line). Nothing changes when the entry is there already.
     */
    static List<String> add(List<String> lines, String name, Path company) {
        List<String> out = new ArrayList<>(lines);
        Extends e = find(out);
        if (e == null) {
            int at = 0;
            while (at < out.size() && out.get(at).startsWith("#")) {
                at++;
            }
            for (int i = 0; i < out.size(); i++) {
                if (out.get(i).matches("fleet\\s*:.*")) {
                    at = i + 1;
                    break;
                }
            }
            out.add(at, "extends:");
            out.add(at + 1, item("  ", name, company));
            return out;
        }
        if (e.indexOf(name) >= 0) {
            return out;
        }
        if (e.inline()) {
            List<String> block = new ArrayList<>();
            block.add("extends:" + e.comment());
            block.add(item("  ", name, company));
            e.values().forEach(v -> block.add("  - " + v));
            out.remove(e.at());
            out.addAll(e.at(), block);
            return out;
        }
        int first = e.items().isEmpty() ? e.at() + 1 : e.items().get(0);
        Matcher firstItem = e.items().isEmpty() ? null : ITEM.matcher(out.get(first));
        String indent = firstItem != null && firstItem.matches() ? firstItem.group(1) : "  ";
        out.add(first, item(indent, name, company));
        return out;
    }

    /** Takes {@code fleet:<name>} out of {@code extends:}, and the {@code extends:} line too when nothing is left in it. */
    static List<String> remove(List<String> lines, String name) {
        List<String> out = new ArrayList<>(lines);
        Extends e = find(out);
        if (e == null || e.indexOf(name) < 0) {
            return out;
        }
        List<String> rest = new ArrayList<>(e.values());
        rest.removeIf(v -> name.equals(name(v)));
        if (e.inline()) {
            if (rest.isEmpty()) {
                out.remove(e.at());
            } else {
                out.set(e.at(), "extends: " + (e.value().startsWith("[") ? "[" + String.join(", ", rest) + "]" : rest.get(0)) + e.comment());
            }
            return out;
        }
        for (int k = e.items().size() - 1; k >= 0; k--) {
            if (name.equals(name(e.values().get(k)))) {
                out.remove((int) e.items().get(k));
            }
        }
        if (rest.isEmpty()) {
            out.remove(e.at());
        }
        return out;
    }

    /**
     * The comment after each {@code - fleet:<name>} item says the path it resolves to on this machine: refreshed when it
     * differs (a comment of the user's own, one not starting with {@code # =}, stays).
     */
    static List<String> refresh(List<String> lines) {
        List<String> out = new ArrayList<>(lines);
        Extends e = find(out);
        if (e == null || e.inline()) {
            return out;
        }
        for (int k = 0; k < e.items().size(); k++) {
            String name = name(e.values().get(k));
            int at = e.items().get(k);
            Matcher m = ITEM.matcher(out.get(at));
            if (name == null || !m.matches() || m.group(3) != null && !m.group(3).strip().startsWith("# =")) {
                continue;
            }
            String pin = pin(e.values().get(k));   // #236: a pinned entry keeps its version
            String line = pin == null ? item(m.group(1), name, company(name))
                    : m.group(1) + "- " + PREFIX + name + "@" + pin + "    " + comment(company(name, pin));
            if (!line.equals(out.get(at))) {
                out.set(at, line);
            }
        }
        return out;
    }

    /** {@link #refresh} on the service's logast.yaml (scan and apply call it); false when nothing changed. */
    public static boolean refresh(Path file) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            List<String> fresh = refresh(lines);
            if (fresh.equals(lines)) {
                return false;
            }
            Files.write(file, fresh);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot edit " + file, e);
        }
    }

    static void edit(Path file, java.util.function.UnaryOperator<List<String>> change) {
        try {
            List<String> lines = Files.exists(file) ? Files.readAllLines(file) : List.of();
            List<String> changed = change.apply(lines);
            if (!changed.equals(lines)) {
                Files.write(file, changed);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot edit " + file, e);
        }
    }
}
