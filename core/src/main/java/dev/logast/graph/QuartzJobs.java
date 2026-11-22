package dev.logast.graph;

import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The repo's Quartz job classes (#187, #208), for the export and for apply's entrypoints (#232): classes that are an
 * {@code org.quartz.Job} (or Spring's {@code QuartzJobBean}), by type when the classpath has Quartz, else by what the
 * source names (with an import of Quartz); classes Quartz code schedules ({@code setJobClass(X.class)},
 * {@code newJob(X.class)}, a helper's argument); and classes extending a job class of the repo.
 */
public final class QuartzJobs {
    public static final Set<String> TYPES = Set.of("org.quartz.Job", "org.quartz.InterruptableJob", "org.quartz.StatefulJob",
            "org.springframework.scheduling.quartz.QuartzJobBean");
    private static final Pattern SCHEDULES_CLASS = Pattern.compile("(?:setJobClass|newJob)\\(\\s*([A-Z]\\w*)\\.class\\s*\\)");
    private static final Pattern JOB_CLASS_PARAM = Pattern.compile("(?:setJobClass|newJob|ofType)\\(\\s*([a-z]\\w*)\\s*\\)");

    private QuartzJobs() {
    }

    /** fqn → class, for every Quartz job class of the repo (abstract ones too: their subclasses are found through them). */
    public static Map<String, ClassInfo> find(CodeIndex index) {
        Map<String, ClassInfo> jobs = new LinkedHashMap<>();
        if (index.classes().stream().noneMatch(c -> quartzFile(c.file().text()))) {
            return jobs;
        }
        List<ClassInfo> classes = index.classes().stream().filter(c -> !c.isInterface()).toList();
        for (ClassInfo c : classes) {
            boolean byType = c.type() != null && TYPES.stream().anyMatch(t -> TypeUtils.isAssignableTo(t, c.type()));
            boolean bySource = quartzFile(c.file().text()) && supertypes(c).stream().anyMatch(n -> TYPES.contains(n)
                    || TYPES.stream().anyMatch(t -> t.endsWith("." + n)));
            if (byType || bySource) {
                jobs.put(c.fqn(), c);
            }
        }
        for (ClassInfo c : scheduledJobClasses(index, classes)) {   // #208: named where it is scheduled (its base class a library's)
            jobs.putIfAbsent(c.fqn(), c);
        }
        boolean more = true;
        while (more) {   // a class extending a job class of the repo, without a classpath to say so
            more = false;
            for (ClassInfo c : classes) {
                if (jobs.containsKey(c.fqn())) {
                    continue;
                }
                for (String n : supertypes(c)) {
                    ClassInfo base = jobs.get(n);
                    if (base == null) {
                        base = jobs.values().stream().filter(j -> j.simpleName().equals(n)).findFirst().orElse(null);
                    }
                    if (base != null) {
                        jobs.put(c.fqn(), c);
                        more = true;
                        break;
                    }
                }
            }
        }
        return jobs;
    }

    /**
     * The methods Quartz calls on a concrete job class: its own {@code execute} / {@code executeInternal}, else the
     * methods it overrides of a base class.
     */
    public static List<MethodInfo> entryMethods(CodeIndex index, ClassInfo c) {
        List<MethodInfo> own = index.methodsOf(c.fqn());
        List<MethodInfo> entry = own.stream().filter(m -> m.name().equals("execute") || m.name().equals("executeInternal")).toList();
        if (entry.isEmpty()) {
            entry = own.stream().filter(m -> m.decl().getLeadingAnnotations().stream()
                    .anyMatch(an -> an.getSimpleName().equals("Override"))).toList();
        }
        return entry;
    }

    static boolean quartzFile(String text) {
        return text.contains("org.quartz") || text.contains("org.springframework.scheduling.quartz");
    }

    /** The names a class extends and implements, as written (generics dropped) and, when resolved, fully qualified. */
    public static List<String> supertypes(ClassInfo c) {
        List<TypeTree> trees = new ArrayList<>();
        if (c.decl().getExtends() != null) {
            trees.add(c.decl().getExtends());
        }
        if (c.decl().getImplements() != null) {
            trees.addAll(c.decl().getImplements());
        }
        List<String> out = new ArrayList<>();
        for (var t : trees) {
            out.add(t.printTrimmed().replaceAll("<.*|\\s+", ""));
            if (t.getType() instanceof JavaType.FullyQualified fq && !(fq instanceof JavaType.Unknown)) {
                out.add(fq.getFullyQualifiedName());
            }
        }
        return out;
    }

    /** #208: methods that build a JobDetail of the class they are given ({@code setJobClass(type)} of a parameter). */
    public static Set<String> helpers(CodeIndex index) {
        Set<String> out = new LinkedHashSet<>();
        for (MethodInfo m : index.methods()) {
            String body = m.decl().getBody() == null ? "" : m.decl().getBody().printTrimmed();
            Matcher p = JOB_CLASS_PARAM.matcher(body);
            while (p.find()) {
                String param = p.group(1);
                if (m.decl().getParameters().stream().anyMatch(x -> x instanceof J.VariableDeclarations vd
                        && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(param)))) {
                    out.add(m.name());
                }
            }
        }
        return out;
    }

    /**
     * #208: the classes Quartz code schedules ({@code setJobClass(X.class)}, {@code newJob(X.class)}, a helper's argument)
     * in a file that imports Quartz; {@code X} as that file sees it (imported, same package, else the only class so named).
     */
    private static Set<ClassInfo> scheduledJobClasses(CodeIndex index, List<ClassInfo> classes) {
        Set<ClassInfo> out = new LinkedHashSet<>();
        Set<String> helpers = null;
        for (MethodInfo m : index.methods()) {
            String file = m.owner().file().text();
            if (m.decl().getBody() == null || !quartzFile(file)) {
                continue;
            }
            String body = m.decl().getBody().printTrimmed();
            List<String> names = new ArrayList<>();
            Matcher sc = SCHEDULES_CLASS.matcher(body);
            while (sc.find()) {
                names.add(sc.group(1));
            }
            if (helpers == null) {
                helpers = helpers(index);
            }
            for (String h : helpers) {
                Matcher hc = Pattern.compile("\\b" + Pattern.quote(h) + "\\(\\s*([A-Z]\\w*)\\.class").matcher(body);
                while (hc.find()) {
                    names.add(hc.group(1));
                }
            }
            String pkg = m.owner().fqn().contains(".") ? m.owner().fqn().substring(0, m.owner().fqn().lastIndexOf('.')) : "";
            for (String n : names) {
                List<ClassInfo> named = classes.stream().filter(c -> c.simpleName().equals(n)).toList();
                ClassInfo pick = named.stream().filter(c -> file.contains("import " + c.fqn() + ";")).findFirst()
                        .or(() -> named.stream().filter(c -> c.fqn().equals(pkg.isEmpty() ? n : pkg + "." + n)).findFirst())
                        .orElse(named.size() == 1 ? named.getFirst() : null);
                if (pick != null) {
                    out.add(pick);
                }
            }
        }
        return out;
    }
}
