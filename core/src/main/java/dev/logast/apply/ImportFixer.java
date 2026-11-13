package dev.logast.apply;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last line of defence after each apply round: the types logast's edits use (support classes, SLF4J) are imported
 * when used and not imported, and their imports removed when nothing uses them any more. Conservative: a simple name
 * that is imported from somewhere else, or declared in the file's own package, is left alone.
 */
final class ImportFixer {
    static final Set<String> SUPPORT = Set.of("MdcScope", "ObservedContext", "ContextPropagation", "ContextExecutors", "TraceContextEnv",
            "AfterCommit");
    private static final Map<String, String> FIXED = Map.of("MDC", "org.slf4j.MDC", "Logger", "org.slf4j.Logger",
            "LoggerFactory", "org.slf4j.LoggerFactory");
    private static final Pattern IMPORT = Pattern.compile("(?m)^import\\s+(static\\s+)?([\\w.]+)(\\.\\*)?\\s*;[^\\n]*\\n?");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");

    private ImportFixer() {
    }

    /** Support class fqns named in edit texts ({@code import com.x.logging.MdcScope;}). */
    static Map<String, String> learn(Collection<String> texts) {
        Map<String, String> out = new HashMap<>();
        Pattern p = Pattern.compile("import\\s+([\\w.]+)\\.(" + String.join("|", SUPPORT) + ")\\s*;");
        for (String t : texts) {
            Matcher m = p.matcher(t);
            while (m.find()) {
                out.put(m.group(2), m.group(1) + "." + m.group(2));
            }
        }
        return out;
    }

    static String fix(String text, Map<String, String> support) {
        Map<String, String> known = new LinkedHashMap<>(FIXED);
        known.putAll(support);
        Matcher pm = PACKAGE.matcher(text);
        String pkg = pm.find() ? pm.group(1) : "";
        Map<String, String> imported = new HashMap<>();   // simple name -> fqn (non-static, non-wildcard)
        Set<String> wildcards = new HashSet<>();
        Matcher im = IMPORT.matcher(text);
        int lastImportEnd = -1;
        while (im.find()) {
            lastImportEnd = im.end();
            if (im.group(1) != null) {
                continue;
            }
            if (im.group(3) != null) {
                wildcards.add(im.group(2));
            } else {
                String fqn = im.group(2);
                imported.put(fqn.substring(fqn.lastIndexOf('.') + 1), fqn);
            }
        }
        // support classes the file already imports teach us their package too
        imported.forEach((simple, fqn) -> {
            if (SUPPORT.contains(simple)) {
                known.putIfAbsent(simple, fqn);
            }
        });
        String code = codeOnly(text);
        String out = text;
        List<String> add = new ArrayList<>();
        for (var e : known.entrySet()) {
            String simple = e.getKey();
            String fqn = e.getValue();
            String fqnPkg = fqn.substring(0, fqn.lastIndexOf('.'));
            boolean used = Pattern.compile("(?<![\\w.])" + simple + "\\b(?!\\s*=)").matcher(code).find();
            boolean importedHere = fqn.equals(imported.get(simple)) || wildcards.contains(fqnPkg) || fqnPkg.equals(pkg);
            boolean clash = imported.containsKey(simple) && !fqn.equals(imported.get(simple))
                    || Pattern.compile("(?m)\\b(class|interface|record|enum)\\s+" + simple + "\\b").matcher(code).find();
            if (used && !importedHere && !clash) {
                add.add("import " + fqn + ";");
            } else if (!used && fqn.equals(imported.get(simple))
                    && !Pattern.compile("(?<![\\w.])" + simple + "\\b").matcher(IMPORT.matcher(text).replaceAll("")).find()) {
                // nothing mentions it any more, not even Javadoc ({@link MDC})
                out = out.replaceFirst("(?m)^import\\s+" + Pattern.quote(fqn) + "\\s*;[^\\n]*\\n", "");
            }
        }
        if (add.isEmpty()) {
            return out;
        }
        String block = String.join("\n", add) + "\n";
        Matcher again = IMPORT.matcher(out);
        int insertAt = -1;
        while (again.find()) {
            insertAt = again.end();
        }
        if (insertAt < 0) {
            Matcher p2 = PACKAGE.matcher(out);
            insertAt = p2.find() ? out.indexOf('\n', p2.end()) + 1 : 0;
            block = (insertAt > 0 ? "\n" : "") + block;
        }
        return out.substring(0, insertAt) + block + out.substring(insertAt);
    }

    /** The source without imports, comments and string/char literals. */
    static String codeOnly(String text) {
        String s = IMPORT.matcher(text).replaceAll("");
        s = s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
        s = s.replaceAll("\"\"\"(?s:.*?)\"\"\"", "\"\"").replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"").replaceAll("'(?:\\\\.|[^'\\\\])'", "''");
        return s;
    }
}
