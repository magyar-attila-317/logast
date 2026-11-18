package dev.logast.rules;

import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.*;

/**
 * METRIC_CATALOG ({@code metrics} pack): metric names live in one place, not at the call sites. Every counting line
 * that names its metric ({@code Outcomes.count("orders", "shipped")}, {@code Outcomes.record(…)}) and every
 * {@code ObservedContext.entrypoint("tally")} becomes a call into a catalog class:
 * {@code OrderMetrics.ordersShipped()}, {@code ObservedContext.entrypoint(JobsMetrics.TALLY)}.
 * <p>{@code metrics.catalog: per-package} (default) keeps one {@code <Package>Metrics} class per package that
 * counts, next to its code; a metric used in several packages lives in the package of its first use.
 * {@code single} puts all of them in {@code metrics.catalogClass}. Catalogs are marked {@code // logast:metrics-catalog};
 * members already there stay as they are (renamed or documented by hand), new ones are added at the end.
 * One order rewrites the catalogs and all call sites together.
 */
final class CatalogRules {
    static final String MARKER = "// logast:metrics-catalog";

    /** What a member stands for: kind count / count-amount / record-amount / name, with its literal values. */
    record Key(String kind, String name, String outcome, String amount) {
    }

    /** A call site to rewrite: the tree replaced, the key, the amount expression's source (or null). */
    private record Use(SourceFileInfo file, J tree, Key key, String amountSrc) {
    }

    /** A catalog class: existing or to create, its members by key, and the members to add. */
    private static final class Catalog {
        final String fqn;
        final SourceFileInfo file;   // null: to create
        final String path;
        final Map<Key, String> members = new LinkedHashMap<>();
        final Set<String> names = new HashSet<>();
        final List<String> added = new ArrayList<>();
        boolean addsMethods;

        Catalog(String fqn, SourceFileInfo file, String path) {
            this.fqn = fqn;
            this.file = file;
            this.path = path;
        }

        String simpleName() {
            return fqn.substring(fqn.lastIndexOf('.') + 1);
        }

        String pkg() {
            return fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
        }
    }

    private final RuleContext ctx;

    CatalogRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    /** Simple names of the catalog classes in the code (calls into them count as counted outcomes). */
    static Set<String> catalogNames(dev.logast.Analysis a) {
        Set<String> out = new HashSet<>();
        for (ClassInfo c : a.index.classes()) {
            if (c.file().text().contains(MARKER)) {
                out.add(c.simpleName());
            }
        }
        return out;
    }

    void run() {
        if (!ctx.enabled(Rules.METRIC_CATALOG) || ctx.policy.metrics().catalog().equals("off")) {
            return;
        }
        Map<String, Catalog> catalogs = existing();
        Set<String> catalogFiles = new HashSet<>();
        catalogs.values().forEach(c -> catalogFiles.add(c.file.relPath()));
        List<Use> uses = new ArrayList<>();
        for (SourceFileInfo f : ctx.a.project.files()) {
            if (!catalogFiles.contains(f.relPath()) && !inSupport(f)) {
                uses.addAll(uses(f));
            }
        }
        if (uses.isEmpty()) {
            return;
        }
        uses.sort(Comparator.comparing((Use u) -> u.file().relPath()).thenComparingInt(u -> Pos.start(u.tree())));

        // each key's member: where it already is, else in the catalog of its first use
        Map<Key, String[]> where = new HashMap<>();   // key -> {catalog fqn, member}
        catalogs.values().forEach(c -> c.members.forEach((k, m) -> where.putIfAbsent(k, new String[]{c.fqn, m})));
        for (Use u : uses) {
            if (where.containsKey(u.key())) {
                continue;
            }
            Catalog c = catalogs.computeIfAbsent(targetFqn(u.file()), fqn -> new Catalog(fqn, null, pathOf(u.file(), fqn)));
            String member = memberName(c, u.key());
            c.members.put(u.key(), member);
            c.names.add(member);
            c.added.add(memberSource(u.key(), member));
            c.addsMethods |= !u.key().kind().equals("name");
            where.put(u.key(), new String[]{c.fqn, member});
        }

        Use first = uses.getFirst();
        // the id names what is moved: new call sites (counting lines another rule just wrote) make a new order, which
        // the same step can apply again
        String moved = uses.stream().map(u -> u.file().relPath() + "|" + u.key()).sorted().reduce("", (a, c) -> a + c + ";");
        OrderBuilder b = ctx.order(Rules.METRIC_CATALOG, "catalog|" + dev.logast.parse.Hashing.sha256(moved).substring(0, 12))
                .at(first.file().relPath(), Pos.line(first.tree()))
                .title(uses.size() + " metric name(s) are spelled out at the call sites: they move into "
                        + (ctx.policy.metrics().catalog().equals("single") ? "one catalog class" : "a catalog class per package"));
        for (Catalog c : catalogs.values()) {
            if (c.added.isEmpty()) {
                continue;
            }
            if (c.file == null) {
                b.edit(Edit.create(c.path, newCatalog(c)));
            } else {
                String text = c.file.text();
                int close = text.lastIndexOf('}');
                b.edit(Edit.insert(c.file.relPath(), close, String.join("", c.added)));
                if (c.addsMethods) {
                    b.edits(JavaEdits.imports(c.file, ctx.support("Outcomes")));
                }
            }
        }
        Map<String, List<Use>> byFile = new LinkedHashMap<>();
        uses.forEach(u -> byFile.computeIfAbsent(u.file().relPath(), k -> new ArrayList<>()).add(u));
        for (List<Use> fileUses : byFile.values()) {
            SourceFileInfo f = fileUses.getFirst().file();
            Set<String> imports = new TreeSet<>();
            for (Use u : fileUses) {
                String[] w = where.get(u.key());
                String cls = w[0].substring(w[0].lastIndexOf('.') + 1);
                String text = u.key().kind().equals("name") ? cls + "." + w[1]
                        : cls + "." + w[1] + "(" + (u.amountSrc() == null ? "" : u.amountSrc()) + ")";
                b.edit(Edit.replace(f.relPath(), Pos.start(u.tree()), Pos.end(u.tree()), text));
                if (!w[0].substring(0, w[0].lastIndexOf('.')).equals(packageOf(f))) {
                    imports.add(w[0]);
                }
            }
            if (!imports.isEmpty()) {
                b.edits(JavaEdits.imports(f, imports.toArray(String[]::new)));
            }
            Edit unused = dropOutcomesImport(f, fileUses);
            if (unused != null) {
                b.edit(unused);
            }
        }
        b.suggestion("Call sites call a catalog member and never name a metric. Rename or document members in the catalog "
                + "freely; logast keeps them and adds new ones at the end. metrics.catalog in logast.yaml: per-package, "
                + "single (metrics.catalogClass) or off.");
    }

    // ---- what is there -----------------------------------------------------------------------------------

    private Map<String, Catalog> existing() {
        Map<String, Catalog> out = new LinkedHashMap<>();
        for (ClassInfo c : ctx.a.index.classes()) {
            if (!c.file().text().contains(MARKER) || !c.file().cu().getClasses().contains(c.decl())) {
                continue;
            }
            Catalog cat = new Catalog(c.fqn(), c.file(), c.file().relPath());
            for (MethodInfo m : ctx.a.index.methodsOf(c.fqn())) {
                cat.names.add(m.name());
                if (m.decl().getBody() != null && m.decl().getBody().getStatements().size() == 1
                        && m.decl().getBody().getStatements().getFirst() instanceof J.MethodInvocation mi) {
                    Key k = countKey(mi);
                    if (k != null) {
                        cat.members.put(k, m.name());
                    }
                }
            }
            for (Statement s : c.decl().getBody().getStatements()) {
                if (s instanceof J.VariableDeclarations vd && vd.hasModifier(J.Modifier.Type.Static)) {
                    for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                        cat.names.add(v.getSimpleName());
                        if (literal(v.getInitializer()) != null) {
                            cat.members.put(new Key("name", literal(v.getInitializer()), "", ""), v.getSimpleName());
                        }
                    }
                }
            }
            out.put(c.fqn(), cat);
        }
        return out;
    }

    private List<Use> uses(SourceFileInfo f) {
        List<Use> out = new ArrayList<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                J.MethodInvocation m = super.visitMethodInvocation(mi, p);
                Key k = countKey(mi);
                if (k != null) {
                    List<Expression> args = args(mi);
                    out.add(new Use(f, mi, k, args.size() == 4 ? f.slice(Pos.start(args.get(3)), Pos.end(args.get(3))) : null));
                } else if (receiverIs(mi, "ObservedContext") && mi.getSimpleName().equals("entrypoint") && args(mi).size() == 1
                        && literal(args(mi).getFirst()) != null) {
                    out.add(new Use(f, args(mi).getFirst(), new Key("name", literal(args(mi).getFirst()), "", ""), null));
                }
                return m;
            }
        }.visit(f.cu(), 0);
        return out;
    }

    /** Outcomes.count(n, o), Outcomes.count(n, o, a, x), Outcomes.record(n, o, a, x) with literal names. */
    private static Key countKey(J.MethodInvocation mi) {
        if (!receiverIs(mi, "Outcomes")) {
            return null;
        }
        List<Expression> args = args(mi);
        // #25: Outcomes.tag("skipped") tags the running timer: the outcome value moves into the catalog too
        if (mi.getSimpleName().equals("tag") && args.size() == 1 && literal(args.getFirst()) != null) {
            return new Key("tag", "", literal(args.getFirst()), "");
        }
        String n = args.size() >= 2 ? literal(args.get(0)) : null;
        String o = args.size() >= 2 ? literal(args.get(1)) : null;
        if (n == null || o == null) {
            return null;
        }
        if (mi.getSimpleName().equals("count") && args.size() == 2) {
            return new Key("count", n, o, "");
        }
        String a = args.size() == 4 ? literal(args.get(2)) : null;
        if (a != null && (mi.getSimpleName().equals("count") || mi.getSimpleName().equals("record"))) {
            return new Key(mi.getSimpleName().equals("count") ? "total" : "per-run", n, o, a);
        }
        return null;
    }

    private static boolean receiverIs(J.MethodInvocation mi, String simpleName) {
        return mi.getSelect() instanceof J.Identifier id && id.getSimpleName().equals(simpleName)
                || mi.getSelect() instanceof J.FieldAccess fa && fa.getSimpleName().equals(simpleName);
    }

    private static String literal(Expression e) {
        return e instanceof J.Literal l && l.getValue() instanceof String s ? s : null;
    }

    private static List<Expression> args(J.MethodInvocation mi) {
        return mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
    }

    private boolean inSupport(SourceFileInfo f) {
        return packageOf(f).equals(ctx.a.supportPackage);
    }

    private static String packageOf(SourceFileInfo f) {
        return f.cu().getPackageDeclaration() == null ? "" : f.cu().getPackageDeclaration().getExpression().printTrimmed();
    }

    // ---- where new members go -------------------------------------------------------------------------------

    private String targetFqn(SourceFileInfo f) {
        if (ctx.policy.metrics().catalog().equals("single")) {
            String cls = ctx.policy.metrics().catalogClass();
            return cls != null ? cls : (ctx.a.appPackage == null ? "" : ctx.a.appPackage + ".") + "metrics.BusinessMetrics";
        }
        String pkg = packageOf(f);
        String last = pkg.isEmpty() ? "App" : pkg.substring(pkg.lastIndexOf('.') + 1);
        String name = Character.toUpperCase(last.charAt(0)) + last.substring(1) + "Metrics";
        String fqn = pkg.isEmpty() ? name : pkg + "." + name;
        ClassInfo taken = ctx.a.index.clazz(fqn);
        if (taken != null && !taken.file().text().contains(MARKER)) {
            fqn = fqn.substring(0, fqn.length() - "Metrics".length()) + "BusinessMetrics";   // a class of that name exists
        }
        return fqn;
    }

    /** Source path of a new catalog: the call site's source root plus the catalog's package. */
    private static String pathOf(SourceFileInfo site, String fqn) {
        String rel = site.relPath();
        String pkgPath = packageOf(site).replace('.', '/');
        int at = pkgPath.isEmpty() ? rel.lastIndexOf('/') + 1 : rel.lastIndexOf(pkgPath + "/");
        String root = at < 0 ? "src/main/java/" : rel.substring(0, at);
        return root + fqn.replace('.', '/') + ".java";
    }

    private static String memberName(Catalog c, Key k) {
        String base;
        if (k.kind().equals("name")) {
            base = String.join("_", words(k.name())).toUpperCase(Locale.ROOT);
        } else if (k.kind().equals("tag")) {
            base = "tag" + capitalize(camel(words(k.outcome())));
        } else {
            base = camel(words(k.name() + "." + k.outcome()));
            // the same outcome with another amount: a second method with the same parameter list needs its own name
            boolean clash = !k.amount().isEmpty() && c.members.keySet().stream().anyMatch(o -> !o.kind().equals("name")
                    && !o.amount().isEmpty() && o.name().equals(k.name()) && o.outcome().equals(k.outcome()) && !o.equals(k));
            if (clash) {
                base = base + capitalize(camel(words(k.amount())));
            }
        }
        // an overload (without and with an amount) may share a method name; anything else gets a number
        String name = base;
        for (int i = 2; c.names.contains(name) && (k.kind().equals("name") || !overloadOk(c, k, name)); i++) {
            name = base + (k.kind().equals("name") ? "_" : "") + i;
        }
        return name;
    }

    /** {@code name()} and {@code name(Number)} can coexist: the existing one must have the other arity. */
    private static boolean overloadOk(Catalog c, Key k, String name) {
        List<Key> same = c.members.entrySet().stream().filter(e -> e.getValue().equals(name)).map(Map.Entry::getKey).toList();
        long methods = c.names.contains(name) ? 1 : 0;   // a name logast did not write (no member) is never shared
        return methods == 1 && !same.isEmpty() && same.size() == 1 && !same.getFirst().kind().equals("name")
                && same.getFirst().amount().isEmpty() != k.amount().isEmpty();
    }

    private static String memberSource(Key k, String member) {
        if (k.kind().equals("name")) {
            return "\n    /** Observation (timer and span) name. */\n"
                    + "    public static final String " + member + " = " + Text.javaString(k.name()) + ";\n";
        }
        if (k.kind().equals("tag")) {
            return "\n    /** outcome=\"" + k.outcome() + "\" on the timer already running (same thread). */\n"
                    + "    public static void " + member + "() {\n"
                    + "        Outcomes.tag(" + Text.javaString(k.outcome()) + ");\n"
                    + "    }\n";
        }
        String meter = k.name() + "{outcome=\"" + k.outcome() + "\"}";
        if (k.amount().isEmpty()) {
            return "\n    /** " + meter + " +1. */\n"
                    + "    public static void " + member + "() {\n"
                    + "        Outcomes.count(" + Text.javaString(k.name()) + ", " + Text.javaString(k.outcome()) + ");\n"
                    + "    }\n";
        }
        String param = camel(words(k.amount()));
        String total = k.name() + "." + k.amount() + "{outcome=\"" + k.outcome() + "\"}";
        boolean perRun = k.kind().equals("per-run");
        return "\n    /** " + meter + " +1; " + (perRun ? param + " is one sample of the distribution " + total
                : total + " grows by " + param) + ". */\n"
                + "    public static void " + member + "(Number " + param + ") {\n"
                + "        Outcomes." + (perRun ? "record" : "count") + "(" + Text.javaString(k.name()) + ", "
                + Text.javaString(k.outcome()) + ", " + Text.javaString(k.amount()) + ", " + param + ");\n"
                + "    }\n";
    }

    private String newCatalog(Catalog c) {
        StringBuilder sb = new StringBuilder();
        if (!c.pkg().isEmpty()) {
            sb.append("package ").append(c.pkg()).append(";\n\n");
        }
        if (c.addsMethods) {
            sb.append("import ").append(ctx.support("Outcomes")).append(";\n\n");
        }
        sb.append("/**\n * Metric names").append(c.pkg().isEmpty() || ctx.policy.metrics().catalog().equals("single") ? ""
                        : " of " + c.pkg())
                .append(", in one place: call sites call a member here and never name a metric.\n")
                .append(" * Generated by logast (metrics.catalog). Rename or document members freely; logast keeps them and adds new\n")
                .append(" * ones at the end.\n */\n")
                .append(MARKER).append('\n')
                .append("public final class ").append(c.simpleName()).append(" {\n")
                .append("    private ").append(c.simpleName()).append("() {\n    }\n");
        c.added.forEach(sb::append);
        return sb.append("}\n").toString();
    }

    /** The Outcomes import, when every use of Outcomes in the file moves into a catalog. */
    private Edit dropOutcomesImport(SourceFileInfo f, List<Use> fileUses) {
        String text = f.text();
        long moved = fileUses.stream().filter(u -> !u.key().kind().equals("name")).count();
        long all = text.split("\\bOutcomes\\.", -1).length - 1L;
        String imp = "import " + ctx.support("Outcomes") + ";";
        int at = text.indexOf(imp);
        if (moved == 0 || moved != all || at < 0) {
            return null;
        }
        int end = text.indexOf('\n', at);
        return Edit.replace(f.relPath(), at, end < 0 ? text.length() : end + 1, "");
    }

    // ---- names ----------------------------------------------------------------------------------------------

    /** "calculated.qaa" + "updated" → [calculated, qaa, updated]; camelCase and separators split. */
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").split("[^A-Za-z0-9]+")) {
            if (!w.isEmpty()) {
                out.add(w.toLowerCase(Locale.ROOT));
            }
        }
        if (out.isEmpty() || Character.isDigit(out.getFirst().charAt(0))) {
            out.addFirst("metric");
        }
        return out;
    }

    static String camel(List<String> words) {
        StringBuilder sb = new StringBuilder(words.getFirst());
        words.subList(1, words.size()).forEach(w -> sb.append(capitalize(w)));
        return sb.toString();
    }

    private static String capitalize(String w) {
        return w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }
}
