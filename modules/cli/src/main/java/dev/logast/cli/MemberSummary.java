package dev.logast.cli;

import dev.logast.export.FlywayExtractor;
import dev.logast.export.RepoExport;
import dev.logast.facts.Edge;
import dev.logast.facts.FactReader;
import dev.logast.facts.Node;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * What a fleet member's repo holds, as one line under its line in the run: the kinds of code logast found in it (Java
 * builds, Helm, TypeORM, Terraform, workflows), whether it has Flyway migrations and whether they ran, and, once its
 * knowledge base is exported, what its facts count (endpoints, jobs, tables, releases, resources, …).
 */
final class MemberSummary {
    private MemberSummary() {
    }

    /**
     * @param repo  the member's checkout
     * @param facts its kb folder when its facts are current (exported or kept in this run), else null
     */
    static String of(Path repo, Path facts) {
        List<RepoExport.Root> roots = RepoExport.roots(repo);
        Counts c = facts == null ? null : Counts.read(facts);
        List<String> parts = new ArrayList<>();
        long java = roots.stream().filter(r -> r.kind().equals("java")).count();
        if (java > 0) {
            parts.add(with(java == 1 ? "Java" : java + " Java builds", c == null ? List.of()
                    : List.of(n(c.nodes("endpoint"), "endpoint"), n(c.nodes("listener"), "listener"), n(c.nodes("job"), "job"),
                    n(c.tablesUsed.size(), "table"), n(c.destinations.size(), "topic/queue", "topics/queues"))));
        }
        if (has(roots, "helm")) {
            parts.add(with("Helm", c == null ? List.of() : List.of(n(c.nodes("release"), "release"), n(c.envs("release"), "environment"))));
        }
        if (has(roots, "node")) {
            parts.add("TypeORM");
        }
        if (has(roots, "terraform")) {
            parts.add(with("Terraform", c == null ? List.of() : List.of(n(c.nodes("resource"), "resource"), n(c.nodes("secret"), "secret"))));
        }
        if (has(roots, "workflows")) {
            parts.add(with("workflows", c == null ? List.of() : List.of(n(c.nodes("workflow"), "workflow"))));
        }
        boolean migrations = roots.stream().anyMatch(r -> r.kind().equals("java") && FlywayExtractor.hasMigrations(r.dir()));
        if (c != null && c.nodes("table") > 0) {   // only Flyway's runs make table nodes; the code's reads and writes are edges
            parts.add("Flyway ran: " + n(c.nodes("table"), "table") + (c.envs("table") > 1 ? " in " + c.envs("table") + " environments" : ""));
        } else if (migrations) {
            parts.add("Flyway migrations, not run (--kb --flyway runs them)");
        }
        return parts.isEmpty() ? "nothing logast reads (no Java build, Helm, Terraform or workflows)" : String.join(" · ", parts);
    }

    private static boolean has(List<RepoExport.Root> roots, String kind) {
        return roots.stream().anyMatch(r -> r.kind().equals(kind));
    }

    private static String with(String what, List<String> counts) {
        List<String> some = counts.stream().filter(s -> !s.isEmpty()).toList();
        return some.isEmpty() ? what : what + ": " + String.join(", ", some);
    }

    private static String n(long count, String one) {
        return n(count, one, one + "s");
    }

    private static String n(long count, String one, String many) {
        return count == 0 ? "" : count + " " + (count == 1 ? one : many);
    }

    /** What a member's facts files hold, counted. */
    private record Counts(Map<String, Long> kinds, Map<String, Set<String>> envsByKind, Set<String> tablesUsed, Set<String> destinations) {
        static Counts read(Path dir) {
            Counts c = new Counts(new LinkedHashMap<>(), new LinkedHashMap<>(), new TreeSet<>(), new TreeSet<>());
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                    try (Reader r = Files.newBufferedReader(f)) {
                        FactReader.Result facts = FactReader.read(r);
                        Set<String> seen = new TreeSet<>();   // a node per environment is one node
                        for (Node node : facts.nodes()) {
                            if (seen.add(node.id())) {
                                c.kinds.merge(node.kind(), 1L, Long::sum);
                            }
                            if (node.env() != null) {
                                c.envsByKind.computeIfAbsent(node.kind(), k -> new TreeSet<>()).add(node.env());
                            }
                        }
                        for (Edge e : facts.edges()) {
                            if ((e.kind().equals("reads") || e.kind().equals("writes")) && e.to().startsWith("table:")) {
                                c.tablesUsed.add(e.to());
                            } else if ((e.kind().equals("publishes") || e.kind().equals("consumes"))
                                    && (e.to().startsWith("topic:") || e.to().startsWith("queue:"))) {
                                c.destinations.add(e.to());
                            }
                        }
                    }
                }
            } catch (IOException e) {
                return null;
            }
            return c;
        }

        long nodes(String kind) {
            return kinds.getOrDefault(kind, 0L);
        }

        long envs(String kind) {
            return envsByKind.getOrDefault(kind, Set.of()).size();
        }
    }
}
