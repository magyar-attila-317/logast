package dev.logast;

import dev.logast.model.ClassInfo;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code logast shape}: the project's code with every own name and word replaced, so its shape can leave the company
 * without its content. The structure stays exactly as it is (classes, calls, lambdas, log lines, MDC scopes), so logast
 * finds the same things in the export as in the original. Replaced: every word of an identifier, string, path and policy
 * value that is not a generic programming word or the name of a public library symbol. Comments are dropped (logast
 * markers stay). The mapping back stays with the person, outside the export folder.
 */
public final class ShapeExport {
    private static final Pattern WORD = Pattern.compile("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|[0-9]+");
    private static final Pattern LETTERS = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Set<String> KEYWORDS = Set.of(("abstract assert boolean break byte case catch char class const continue "
            + "default do double else enum extends final finally float for goto if implements import instanceof int interface long "
            + "native new package private protected public return short static strictfp super switch synchronized this throw throws "
            + "transient try void volatile while true false null var record yield sealed permits non when").split(" "));
    /** Programming and logging words: they say nothing about a company and keep logast's name-based recognition working. */
    private static final Set<String> BASE_GENERIC = new HashSet<>(Arrays.asList(("a an the of to in on at by for from with without "
            + "and or not no is are was be been has have had do does did get set put add remove delete update insert select create "
            + "find load save store read write send receive publish consume handle process run start started stop stopped complete "
            + "completed fail failed failure success succeeded skip skipped retry retrying done finish finished begin end close open "
            + "check checked validate validation valid invalid validator validators calculate calculated calculator calculation "
            + "compute evaluate evaluated result results outcome status state error errors exception warn warning info debug trace "
            + "log logger logs logging message messages event events listener handler service services repository repo dao "
            + "controller client server request response job jobs task tasks scheduler schedule scheduled batch step item items row "
            + "rows record records entity entities dto model mapper config configuration properties property factory builder "
            + "helper helpers util utils utility support context scope mdc key keys value values id ids uuid code name type kind "
            + "count total size amount number sum min max first last next prev previous all any none one two list map set "
            + "queue stream page sort order sorted filter match matching matched apply applied test tested source target "
            + "input output data date day days time timestamp duration from to since until now today new old current default "
            + "main app application api impl base abstract simple internal external public local global http rest kafka topic "
            + "sql jdbc db database table column query where into values join inner left right group having limit offset as "
            + "on case when then else like between exists distinct count union null not and or desc asc returning "
            + "user users account accounts customer customers request requests file files path url uri host port "
            + "cache lock retry timeout async sync parallel thread threads executor future callback "
            + "string int long boolean object array optional empty present parse format to string of equals hash "
            + "spring boot java javax jakarta org com io net lombok slf4j jackson micrometer opentelemetry otel junit "
            + "example test tests mock received called used loaded requested invoked queued enabled disabled configured "
            + "initialized initialised registered sent rejected cancelled built").split(" ")));

    private final Set<String> generic = new HashSet<>(BASE_GENERIC);
    private final Map<String, String> aliases = new LinkedHashMap<>();   // lower-case word -> alias
    private final Set<String> protectedNames = new HashSet<>();           // identifiers of public library symbols
    private final Set<String> own = new HashSet<>();
    private String internalPrefix;

    public record Result(Path dir, Path mapping, int files, int words) {
    }

    public static Result write(Analysis a, Path out, Path mappingFile) throws IOException {
        ShapeExport s = new ShapeExport();
        return s.run(a, out, mappingFile);
    }

    private Result run(Analysis a, Path out, Path mappingFile) throws IOException {
        for (ClassInfo c : a.index.classes()) {
            own.add(c.fqn());
            String pkg = c.type().getPackageName();
            if (!pkg.isEmpty()) {
                ownPackages.add(pkg);
            }
        }
        String app = a.appPackage == null ? "" : a.appPackage;
        String[] seg = app.split("\\.");
        internalPrefix = seg.length >= 2 ? seg[0] + "." + seg[1] + "." : null;   // the company's own libraries share it
        try (InputStream in = ShapeExport.class.getResourceAsStream("/logast-defaults.yaml")) {
            if (in != null) {
                Matcher m = LETTERS.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                while (m.find()) {
                    words(m.group()).forEach(w -> generic.add(w.toLowerCase(Locale.ROOT)));
                }
            }
        }
        for (SourceFileInfo f : a.project.files()) {
            collectOwnNames(f.cu());
        }
        for (SourceFileInfo f : a.project.files()) {
            collectProtected(f.cu());
        }
        for (String p : protectedNames) {
            words(p).forEach(w -> generic.add(w.toLowerCase(Locale.ROOT)));
        }
        protectedNames.addAll(guessedNames);   // whole names only: their words are not made generic
        deleteDir(out);
        int files = 0;
        for (SourceFileInfo f : a.project.files()) {
            Path target = out.resolve(mapPath(f.relPath()));
            Files.createDirectories(target.getParent());
            Files.writeString(target, mapJava(f.text()));
            files++;
        }
        policyNames(dev.logast.policy.Policy.class, new HashSet<>());
        protectedNames.addAll(List.of("vendor", "dependency", "logstash", "ecs", "gelf", "size", "never", "always"));
        for (String name : List.of("logast.yml", "logast.yaml")) {
            Path p = a.project.root().resolve(name);
            if (Files.exists(p)) {
                Files.writeString(out.resolve(name), mapYaml(Files.readString(p)));
            }
        }
        Files.writeString(out.resolve("libraries.txt"), libraries(a.project.classpath()));
        Files.writeString(out.resolve("README.txt"), """
                Made by `logast shape`: the code's structure with its own names and words replaced (qa, qb, ...).
                Kept: generic programming words, public library names (Spring, SLF4J, Lombok, ...), annotations,
                the structure of every class and method. Dropped: comments, resources, build files.
                Read it before sending: anything that should not leave, delete or replace it. The mapping back
                (shape-mapping.txt) is NOT in this folder and should stay with you.
                """);
        StringBuilder map = new StringBuilder("# alias -> original word (keep this file; it is not part of the export)\n");
        aliases.forEach((word, alias) -> map.append(alias).append(" = ").append(word).append('\n'));
        Files.createDirectories(mappingFile.getParent());
        Files.writeString(mappingFile, map.toString());
        return new Result(out, mappingFile, files, aliases.size());
    }

    /** Setting names of the policy (records and their nested types): they stay, only vocabulary words are replaced. */
    private void policyNames(Class<?> c, Set<Class<?>> seen) {
        if (c == null || !seen.add(c) || !c.getName().startsWith("dev.logast")) {
            return;
        }
        if (c.isRecord()) {
            for (var rc : c.getRecordComponents()) {
                protectedNames.add(rc.getName());
                var jp = rc.getAccessor().getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
                if (jp != null) {
                    protectedNames.add(jp.value());
                }
                policyNames(rc.getType(), seen);
            }
        }
        for (var f : c.getDeclaredFields()) {
            protectedNames.add(f.getName());
        }
        if (c.isEnum()) {
            for (Object e : c.getEnumConstants()) {
                protectedNames.add(e.toString());
                protectedNames.add(e.toString().toLowerCase(Locale.ROOT));
            }
        }
        for (Class<?> inner : c.getDeclaredClasses()) {
            policyNames(inner, seen);
        }
    }

    /** Names declared in the code (methods, fields, classes): an unresolved name that is none of them is a library's. */
    private final Set<String> ownNames = new HashSet<>();
    /** Unresolved library names (no classpath for them): kept as whole names. */
    private final Set<String> guessedNames = new HashSet<>();

    private void collectOwnNames(J.CompilationUnit cu) {
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, Integer p) {
                ownNames.add(md.getSimpleName());
                return super.visitMethodDeclaration(md, p);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                ownNames.add(c.getSimpleName());
                return super.visitClassDeclaration(c, p);
            }

            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, Integer p) {
                String n = v.getSimpleName();
                ownNames.add(n);
                if (!n.isEmpty()) {
                    String cap = Character.toUpperCase(n.charAt(0)) + n.substring(1);
                    ownNames.addAll(List.of("get" + cap, "set" + cap, "is" + cap, "with" + cap));   // Lombok accessors
                }
                return super.visitVariable(v, p);
            }
        }.visit(cu, 0);
    }

    private boolean unresolved(JavaType.Method mt) {
        return mt == null || mt.getDeclaringType() == null || mt.getDeclaringType() instanceof JavaType.Unknown;
    }

    /** Own packages (of source classes), to tell own imports from library ones without type resolution. */
    private final Set<String> ownPackages = new HashSet<>();

    /** An import of a public library: not own code, not under the company's prefix. Kept as written. */
    private boolean externalImport(String path) {
        String p = path.replaceFirst("^static\\s+", "").strip();
        if (internalPrefix != null && p.startsWith(internalPrefix)) {
            return false;
        }
        String owner = p.endsWith(".*") ? p.substring(0, p.length() - 2) : p;
        for (String pkg : ownPackages) {
            if (owner.equals(pkg) || owner.startsWith(pkg + ".")) {
                return false;
            }
        }
        return !own.contains(owner);
    }

    // ---- what must keep its name: symbols of public libraries ----

    private boolean library(JavaType t) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
        if (fq == null || fq instanceof JavaType.Unknown) {
            return false;
        }
        String fqn = fq.getFullyQualifiedName();
        return !own.contains(fqn) && !own.contains(fqn.replace('$', '.')) && (internalPrefix == null || !fqn.startsWith(internalPrefix));
    }

    private void protectType(JavaType t) {
        if (!library(t)) {
            return;
        }
        String fqn = TypeUtils.asFullyQualified(t).getFullyQualifiedName();
        protectedNames.addAll(Arrays.asList(fqn.split("[.$]")));
    }

    private void protectMethod(JavaType.Method mt) {
        if (mt == null) {
            return;
        }
        if (library(mt.getDeclaringType())) {
            protectedNames.add(mt.getName());
            protectType(mt.getDeclaringType());
            return;
        }
        Optional<JavaType.Method> overridden = TypeUtils.findOverriddenMethod(mt);
        if (overridden.isPresent() && library(overridden.get().getDeclaringType())) {
            protectedNames.add(mt.getName());   // run(), validate() of a library interface, toString(), ...
        }
    }

    private void collectProtected(J.CompilationUnit cu) {
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                protectMethod(mi.getMethodType());
                if (unresolved(mi.getMethodType()) && !ownNames.contains(mi.getSimpleName())) {
                    guessedNames.add(mi.getSimpleName());   // ResponseEntity.ok().body(..) without the jar
                }
                return super.visitMethodInvocation(mi, p);
            }

            @Override
            public J.MemberReference visitMemberReference(J.MemberReference r, Integer p) {
                protectMethod(r.getMethodType());
                return super.visitMemberReference(r, p);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass n, Integer p) {
                protectType(n.getType());
                return super.visitNewClass(n, p);
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, Integer p) {
                protectMethod(md.getMethodType());
                return super.visitMethodDeclaration(md, p);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier id, Integer p) {
                protectType(id.getType() instanceof JavaType.FullyQualified ? id.getType() : null);
                JavaType.Variable v = id.getFieldType();
                if (v != null && v.getOwner() instanceof JavaType.FullyQualified owner && library(owner)) {
                    protectedNames.add(id.getSimpleName());   // BigDecimal.ZERO, System.out
                }
                return super.visitIdentifier(id, p);
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation an, Integer p) {
                String simple = an.getSimpleName();
                boolean ownAnnotation = ownNames.contains(simple) && !library(an.getType());
                if (!ownAnnotation) {
                    guessedNames.add(simple);   // @PostMapping from a wildcard import, resolved or not
                }
                if (!ownAnnotation && an.getArguments() != null) {
                    for (var arg : an.getArguments()) {
                        if (arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier name) {
                            protectedNames.add(name.getSimpleName());   // @Scheduled(cron = ...)
                        }
                    }
                }
                return super.visitAnnotation(an, p);
            }

            @Override
            public J.Import visitImport(J.Import i, Integer p) {
                String path = i.getQualid().printTrimmed().replaceAll("\\s+", "");
                if (externalImport(path)) {
                    // the class (or static member) name stays wherever the code uses it, resolved or not
                    String[] parts = path.split("\\.");
                    protectedNames.addAll(Arrays.asList(parts).subList(0, parts.length).stream().filter(x -> !x.equals("*")).toList());
                }
                protectType(i.getQualid().getType());
                if (i.isStatic() && i.getQualid().getTarget() instanceof J.FieldAccess t && library(t.getType())) {
                    protectedNames.add(i.getQualid().getSimpleName());
                }
                return super.visitImport(i, p);
            }
        }.visit(cu, 0);
    }

    // ---- mapping ----

    private static List<String> words(String identifier) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(identifier);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private String alias(String lower) {
        return aliases.computeIfAbsent(lower, w -> {
            int n = aliases.size();
            StringBuilder sb = new StringBuilder();
            do {
                sb.insert(0, (char) ('a' + n % 26));
                n = n / 26 - 1;
            } while (n >= 0);
            return "q" + sb;
        });
    }

    /** couponId → qaId, CouponValidators → QaValidators, COUPON_ID → QA_ID: every non-generic word, same alias everywhere. */
    String mapIdentifier(String id) {
        if (KEYWORDS.contains(id) || protectedNames.contains(id)) {
            return id;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = WORD.matcher(id);
        int last = 0;
        while (m.find()) {
            sb.append(id, last, m.start());
            String w = m.group();
            String lower = w.toLowerCase(Locale.ROOT);
            if (Character.isDigit(w.charAt(0)) || generic.contains(lower) || w.length() == 1) {
                sb.append(w);
            } else {
                // a past-tense ending stays (logast reads "X published" as an outcome): publish → qa, published → qaed
                boolean past = lower.length() > 4 && lower.endsWith("ed");
                String a = past ? alias(lower.substring(0, lower.length() - 2)) + "ed" : alias(lower);
                boolean allCaps = w.length() > 1 && w.equals(w.toUpperCase(Locale.ROOT));
                sb.append(allCaps ? a.toUpperCase(Locale.ROOT)
                        : Character.isUpperCase(w.charAt(0)) ? Character.toUpperCase(a.charAt(0)) + a.substring(1) : a);
            }
            last = m.end();
        }
        sb.append(id.substring(last));
        return sb.toString();
    }

    private String mapText(String s) {
        Matcher m = LETTERS.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(mapIdentifier(m.group())));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String mapPath(String rel) {
        String[] parts = rel.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (i < 3 && List.of("src", "main", "java").contains(p)) {
                sb.append(p);
            } else if (p.endsWith(".java")) {
                sb.append(mapIdentifier(p.substring(0, p.length() - 5))).append(".java");
            } else {
                sb.append(mapIdentifier(p));
            }
            sb.append(i < parts.length - 1 ? "/" : "");
        }
        return sb.toString();
    }

    /** Java source: identifiers and words in strings mapped, comments dropped (line breaks and logast markers kept). */
    String mapJava(String src) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
                String comment = src.substring(i, end);
                int marker = comment.indexOf("logast:");
                if (marker >= 0) {
                    out.append("// ").append(mapText(comment.substring(marker)));
                }
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                src.substring(i, end).chars().filter(ch -> ch == '\n').forEach(ch -> out.append('\n'));
                i = end;
            } else if (src.startsWith("\"\"\"", i)) {
                int end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(mapText(src.substring(i, end)));
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && src.charAt(j) != c && src.charAt(j) != '\n') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j + 1, n);
                out.append(c == '"' ? mapText(src.substring(i, j)) : src.substring(i, j));
                i = j;
            } else if (src.startsWith("import", i) && (i == 0 || src.charAt(i - 1) == '\n') && i + 6 < n
                    && Character.isWhitespace(src.charAt(i + 6))) {
                int end = src.indexOf(';', i);
                end = end < 0 ? n : end + 1;
                String stmt = src.substring(i, end);
                out.append(externalImport(stmt.substring(6, stmt.length() - 1).replaceAll("\\s+", " ").strip()
                        .replace(" ", "").replaceFirst("^static", "static ")) ? stmt : mapImport(stmt));
                i = end;
            } else if (Character.isJavaIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && Character.isJavaIdentifierPart(src.charAt(j))) {
                    j++;
                }
                boolean annotation = i > 0 && src.charAt(i - 1) == '@';
                String id = src.substring(i, j);
                out.append(annotation && Character.isUpperCase(id.charAt(0)) && protectedNames.contains(id) ? id : mapIdentifier(id));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i + 1;
                while (j < n && (Character.isLetterOrDigit(src.charAt(j)) || src.charAt(j) == '.' || src.charAt(j) == '_')) {
                    j++;
                }
                out.append(src, i, j);   // numbers (and their L/f/x suffixes) as they are
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private String mapImport(String stmt) {
        Matcher m = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*").matcher(stmt);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(id.equals("import") || id.equals("static") ? id : mapIdentifier(id)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Policy YAML: comments dropped, every non-generic word mapped the same way as in the code. */
    String mapYaml(String yaml) {
        StringBuilder sb = new StringBuilder();
        for (String line : yaml.split("\n", -1)) {
            if (!line.strip().startsWith("#")) {
                sb.append(mapText(line.replaceAll("\\s+#.*$", ""))).append('\n');
            }
        }
        return sb.toString();
    }

    /** Public libraries on the classpath (group:artifact:version); the company's own ones are mapped like the code. */
    private String libraries(List<Path> classpath) {
        Set<String> out = new TreeSet<>();
        for (Path p : classpath) {
            String s = p.toString().replace('\\', '/');
            int at = s.indexOf("/files-2.1/");
            if (at < 0) {
                continue;
            }
            String[] g = s.substring(at + 11).split("/");
            if (g.length < 3) {
                continue;
            }
            String coord = g[0] + ":" + g[1] + ":" + g[2];
            boolean internal = internalPrefix != null && (g[0] + ".").startsWith(internalPrefix);
            out.add(internal ? mapText(coord) : coord);
        }
        return String.join("\n", out) + "\n";
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
