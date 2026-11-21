package dev.logast.export;

import dev.logast.Analysis;
import dev.logast.Logast;
import dev.logast.facts.FactWriter;
import dev.logast.facts.Header;
import dev.logast.fleet.NamedFleet;
import dev.logast.orders.ScanResult;
import dev.logast.policy.Policy;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;

/** #153: {@code logast export}: a service's facts as a facts file (docs/facts.md). */
public final class Export {
    private Export() {
    }

    /**
     * @param repo    the repository name the facts are about (null: the root folder's name)
     * @param version logast's version, written as the extractor's version
     */
    public static void java(Path root, List<Path> classpath, Policy policy, String repo, String version, Writer out) throws IOException {
        java(root, classpath, policy, repo, version, null, out);
    }

    /** @param flyway null: code facts only; else also the tables (and rows) the migrations create, per environment (#157) */
    public static void java(Path root, List<Path> classpath, Policy policy, String repo, String version, FlywayOptions flyway,
                            Writer out) throws IOException {
        java(root, classpath, policy, repo, version, flyway, out, null, true);
    }

    /**
     * @param rootPrefix the root's folder in the repo (#182), written into every fact's evidence; null at the repo's top
     * @param repoFacts  also the repo node, its libraries and its workflows (at the repo's top; once per repo)
     */
    public static void java(Path root, List<Path> classpath, Policy policy, String repo, String version, FlywayOptions flyway,
                            Writer out, String rootPrefix, boolean repoFacts) throws IOException {
        java(root, analyse(root, classpath, policy), repo, version, flyway, out, rootPrefix, repoFacts)
                .forEach(p -> System.err.println("export: " + p));
    }

    /** #184: an analysis and its scan, made once and shared (the fleet's scan and the export of the same root). */
    public record Analysed(Analysis analysis, ScanResult scan, List<Path> classpath) {
    }

    /**
     * Analyses a root without touching logast's last-analysis statics, so exports can run side by side (#184): as
     * {@link Logast#scan}, the analysis with the root's traffic file, then the rules.
     */
    public static Analysed analyse(Path root, List<Path> classpath, Policy policy) {
        Path r = root.toAbsolutePath().normalize();
        Analysis a = Logast.analyse(r, classpath, policy).withTraffic(dev.logast.Traffic.load(r, null));
        return new Analysed(a, dev.logast.rules.Rules.scan(a), classpath);
    }

    /** The facts of a root from an analysis already made; returns what went wrong (the migrations that could not run). */
    public static List<String> java(Path root, Analysed analysed, String repo, String version, FlywayOptions flyway,
                                    Writer out, String rootPrefix, boolean repoFacts) throws IOException {
        List<String> problems = new java.util.ArrayList<>();
        Path r = root.toAbsolutePath().normalize();
        ScanResult scan = analysed.scan();
        Analysis a = analysed.analysis();
        List<Path> classpath = analysed.classpath();
        String commit = NamedFleet.commit(r);
        String extractor = flyway == null ? JavaExtractor.ID : JavaExtractor.ID + "+" + FlywayExtractor.ID;
        String repoName = repo != null ? repo : NamedFleet.serviceName(r);
        try (FactWriter fw = new FactWriter(out, Header.of(extractor, version, repoName, commit))) {
            dev.logast.facts.FactSink w = dev.logast.facts.FactSinks.rooted(fw, rootPrefix);
            new JavaExtractor(a, scan, classpath).extract(r, w);
            String service = JavaExtractor.serviceName(r, dev.logast.fleet.ServiceSurface.of(a, scan));
            GradleProjects.extract(r, repoName, service, w);   // #162: the repo, and the libraries it publishes
            if (repoFacts) {
                problems.addAll(workflows(r, repoName, w));
            }
            if (flyway != null) {
                FlywayExtractor fx = new FlywayExtractor(service, flyway.rows(), flyway.image())
                        .withCodeRuns(ProgrammaticFlyway.find(a.index))   // #179: the runs the code makes (per tenant)
                        .withClasspath(classpath)   // #181: migrations a library ships in its jar
                        .withElsewhere(flyway.elsewhere());   // #205: what other services' migrations create
                fx.extract(r, w);
                problems.addAll(fx.problems());
            }
        }
        return problems;
    }

    /**
     * #160: a Helm repo's facts per environment.
     *
     * @param rendered a folder of {@code <env>.yaml} manifests rendered elsewhere, instead of running helmfile; or null
     * @return what went wrong per environment (the others are still written)
     */
    public static List<String> helm(Path root, String repo, String version, List<String> envs, List<String> render, Path rendered,
                                    Writer out) throws IOException {
        return helm(root, repo, version, envs, render, rendered, out, null, true);
    }

    /** #182: {@code rootPrefix} the helmfile's folder in the repo; {@code repoFacts} also the repo's workflows. */
    public static List<String> helm(Path root, String repo, String version, List<String> envs, List<String> render, Path rendered,
                                    Writer out, String rootPrefix, boolean repoFacts) throws IOException {
        Path r = root.toAbsolutePath().normalize();
        HelmExtractor hx = new HelmExtractor(envs, render, rendered);
        try (FactWriter fw = new FactWriter(out, Header.of(HelmExtractor.ID, version, repo != null ? repo : NamedFleet.serviceName(r),
                NamedFleet.commit(r)))) {
            dev.logast.facts.FactSink w = dev.logast.facts.FactSinks.rooted(fw, rootPrefix);
            hx.extract(r, w);
            List<String> p = new java.util.ArrayList<>(hx.problems());
            if (repoFacts) {
                p.addAll(workflows(r, repo != null ? repo : NamedFleet.serviceName(r), w));
            }
            return p;
        }
    }

    /** #166: a Terragrunt/Terraform repo's resources per environment; returns what could not be read. */
    public static List<String> terraform(Path root, String repo, String version, Writer out) throws IOException {
        return terraform(root, repo, version, out, true);
    }

    /** #182: {@code repoFacts} also the repo's workflows. */
    public static List<String> terraform(Path root, String repo, String version, Writer out, boolean repoFacts) throws IOException {
        Path r = root.toAbsolutePath().normalize();
        TerraformExtractor tx = new TerraformExtractor();
        try (FactWriter w = new FactWriter(out, Header.of(TerraformExtractor.ID, version, repo != null ? repo : NamedFleet.serviceName(r),
                NamedFleet.commit(r)))) {
            tx.extract(r, w);
            List<String> p = new java.util.ArrayList<>(tx.problems());
            if (repoFacts) {
                p.addAll(workflows(r, repo != null ? repo : NamedFleet.serviceName(r), w));
            }
            return p;
        }
    }

    /** #167: only the workflows of a repo that has nothing else logast reads (a CI repo). */
    public static List<String> workflowsOnly(Path root, String repo, String version, Writer out) throws IOException {
        Path r = root.toAbsolutePath().normalize();
        try (FactWriter w = new FactWriter(out, Header.of(WorkflowExtractor.ID, version, repo != null ? repo : NamedFleet.serviceName(r),
                NamedFleet.commit(r)))) {
            return workflows(r, repo != null ? repo : NamedFleet.serviceName(r), w);
        }
    }

    /** #167: the repo's GitHub Actions workflows, named {@code owner/repo} as other repos' {@code uses:} lines name it. */
    static List<String> workflows(Path root, String repo, dev.logast.facts.FactSink out) {
        if (AnsibleRunners.applies(root)) {   // #173: self-hosted runners an Ansible inventory sets up
            AnsibleRunners.extract(root, out);
        }
        if (!WorkflowExtractor.applies(root)) {
            return List.of();
        }
        WorkflowExtractor wx = new WorkflowExtractor(githubName(root, repo));
        wx.extract(root, out);
        return wx.problems();
    }

    /** {@code owner/name} from the {@code origin} remote's URL (GitHub's https or ssh form), else the given name. */
    static String githubName(Path root, String fallback) {
        try {
            Process p = new ProcessBuilder("git", "-C", root.toString(), "remote", "get-url", "origin").redirectErrorStream(true).start();
            String url = new String(p.getInputStream().readAllBytes()).strip();
            if (p.waitFor() == 0) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("github\\.com[:/]([^/]+/[^/]+?)(?:\\.git)?$").matcher(url);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (IOException e) {
            // no git: the given name
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return fallback;
    }

    /** What {@code --flyway} asks for: the tables whose rows become facts, and the PostgreSQL image. */
    public record FlywayOptions(List<String> rows, String image, java.util.Map<String, List<String>> elsewhere) {
        public FlywayOptions(List<String> rows, String image) {
            this(rows, image, java.util.Map.of());
        }
    }
}
