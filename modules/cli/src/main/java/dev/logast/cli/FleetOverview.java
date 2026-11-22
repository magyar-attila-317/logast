package dev.logast.cli;

import dev.logast.Analysis;
import dev.logast.CentralVocabulary;
import dev.logast.Logast;
import dev.logast.LogastDir;
import dev.logast.fleet.CompanyPolicy;
import dev.logast.fleet.FleetHome;
import dev.logast.fleet.FleetExtends;
import dev.logast.fleet.FleetLinks;
import dev.logast.fleet.FleetRepos;
import dev.logast.fleet.FleetScaffold;
import dev.logast.fleet.FleetReport;
import dev.logast.fleet.FleetTidy;
import dev.logast.fleet.NamedFleet;
import dev.logast.orders.Ansi;
import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.policy.Policy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** `logast fleet <name>`: the overview of a fleet's members from their snapshots, as text or JSON, and its report page. */
final class FleetOverview {
    private FleetOverview() {
    }

    /** What an overview shows, built once for the JSON and the text form. */
    record Overview(Map<String, NamedFleet.Snapshot> snaps, FleetLinks.Result links, CentralVocabulary.Result vocab,
                            Path company, List<CompanyPolicy.Suggestion> suggestions, String agreed, List<String> next, Path page) {
    }

    /**
     * The overview's content from the snapshots: links between services, the vocabulary (#139: the worksheet and company.yaml
     * kept current once a worksheet exists), company.yaml's suggestions (#129), the next steps (#149) and the page (#141).
     */
    static Overview overview(String name, Path dir, Map<String, Path> members, Path out, boolean vocabulary) throws IOException {
        Map<String, NamedFleet.Snapshot> snaps = NamedFleet.snapshots(dir);
        List<FleetReport.Service> services = snaps.values().stream().map(NamedFleet.Snapshot::report).toList();
        FleetLinks.Result links = FleetLinks.of(services);
        CentralVocabulary.Result vocab = NamedFleet.vocabulary(dir, snaps.values());
        // #139: every overview keeps the worksheet and company.yaml's vocabulary current once a worksheet exists (decisions
        // are kept), so deciding is: edit the worksheet, run `logast fleet <name>`; --vocabulary writes the first one
        if (vocabulary || Files.exists(dir.resolve(CentralVocabulary.WORKSHEET))) {
            Files.writeString(dir.resolve(CentralVocabulary.WORKSHEET), vocab.worksheet());
            CentralVocabulary.writeSchema(dir);
            CentralVocabulary.writeCompany(dir.resolve(CentralVocabulary.COMPANY), vocab.company());
        }
        // #129: what the members agree on, as commented suggestions in company.yaml
        Path company = dir.resolve(CentralVocabulary.COMPANY);
        List<CompanyPolicy.Suggestion> suggestions = CompanyPolicy.refresh(dir, name, snaps.values());
        String agreed = CompanyPolicy.overviewLine(suggestions, company);
        // #149: what to do next, on the page and at the end of the overview
        List<String> next = dev.logast.fleet.FleetNext.steps(name, dir, members, NamedFleet.joined(dir), snaps, suggestions, links, vocab);
        // #141: every overview rewrites the page (--html only says so explicitly)
        Path page = FleetReport.writeDaily(out != null ? out : dir.resolve("report"), services, links,
                suggestions.stream().map(CompanyPolicy.Suggestion::text).toList(), next);
        return new Overview(snaps, links, vocab, company, suggestions, agreed, next, page);
    }

    static int printJson(String name, Path dir, Map<String, Path> members, Map<String, Path> added, Overview ov) throws IOException {
        Map<String, NamedFleet.Snapshot> snaps = ov.snaps();
        FleetLinks.Result links = ov.links();
        CentralVocabulary.Result vocab = ov.vocab();
        Path company = ov.company();
        List<CompanyPolicy.Suggestion> suggestions = ov.suggestions();
        List<String> next = ov.next();
        Path page = ov.page();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("fleet", name);
        o.put("dir", dir.toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> names = new java.util.TreeSet<>(members.keySet());
        names.addAll(snaps.keySet());
        for (String s : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", s);
            row.put("root", members.containsKey(s) ? members.get(s).toString() : snaps.get(s).root());
            row.put("member", !members.containsKey(s) ? "former" : added.containsKey(s) ? "added" : "joined");   // #132
            NamedFleet.Snapshot snap = snaps.get(s);
            if (snap != null) {
                row.put("score", snap.report().score());
                row.put("open", open(snap.report()));
                row.put("severity", snap.report().severity());
                row.put("snapshot", snap.created());
                row.put("commit", snap.commit());
            }
            rows.add(row);
        }
        o.put("services", rows);
        o.put("links", links);
        o.put("vocabulary", Map.of("summary", vocab.summary(), "conflicts", vocab.conflicts()));
        o.put("company", Map.of("file", company.toString(), "suggestions", suggestions));
        o.put("next", next);
        if (page != null) {
            o.put("report", page.toAbsolutePath().toString());
        }
        System.out.println(OrderJson.MAPPER.writeValueAsString(o));
        return 0;
    }

    static int printOverview(String name, Path dir, Map<String, Path> members, Map<String, Path> added, Overview ov,
                             boolean vocabulary, boolean openPage) {
        Map<String, NamedFleet.Snapshot> snaps = ov.snaps();
        FleetLinks.Result links = ov.links();
        CentralVocabulary.Result vocab = ov.vocab();
        Path company = ov.company();
        String agreed = ov.agreed();
        List<String> next = ov.next();
        Path page = ov.page();
        Instant now = Instant.now();
        section("fleet " + name);   // #225: the overview in the phases' style
        System.out.println("   " + Ansi.dim(dir + "  (" + members.size() + (members.size() == 1 ? " member)" : " members)")));
        Set<String> names = new java.util.TreeSet<>(members.keySet());
        names.addAll(snaps.keySet());
        int w = Math.min(36, names.stream().mapToInt(String::length).max().orElse(8)) + 2;
        for (String s : names) {
            NamedFleet.Snapshot snap = snaps.get(s);
            // #132: added (the repo knows nothing of the fleet) or joined (fleet: in its logast.yaml)
            String pad = "  " + FleetLog.fit(s, w) + Ansi.dim(FleetLog.fit(!members.containsKey(s) ? "" : added.containsKey(s) ? "added" : "joined", 8));
            if (snap == null) {
                System.out.println(pad + Ansi.dim(noSnapshot(name, members.get(s), added.containsKey(s))));
                continue;
            }
            FleetReport.Service r = snap.report();
            int high = r.severity().getOrDefault("high", 0);
            String score = String.format("score %5.1f", r.score());
            System.out.println(pad + (r.score() >= 80 ? Ansi.green(score) : r.score() >= 50 ? Ansi.yellow(score) : Ansi.red(score))
                    + String.format("  open %3d (", open(r)) + (high > 0 ? Ansi.red("high " + high) : "high 0")
                    + String.format(", medium %d, low %d)", r.severity().getOrDefault("medium", 0), r.severity().getOrDefault("low", 0))
                    + Ansi.dim(String.format("  snapshot %s%s%s", age(snap.created(), now), snap.commit() == null ? "" : ", " + snap.commit(),
                    members.containsKey(s) ? "" : " (not a member any more)")));
        }
        // #133: what `fleet tidy` would remove from each joined member's logast.yaml (read only)
        List<String> tidy = new ArrayList<>();
        for (var m : NamedFleet.joined(dir).entrySet()) {
            if (Files.isDirectory(m.getValue())) {
                String line = FleetTidy.plan(m.getValue(), company).overviewLine(m.getKey(), name);
                if (line != null) {
                    tidy.add(line);
                }
            }
        }
        if (!tidy.isEmpty()) {
            System.out.println();
            System.out.println("Own settings next to company.yaml:");
            tidy.forEach(l -> System.out.println("  " + l));
        }
        section("across services");
        for (String l : FleetLinks.text(links).split("\n")) {   // #225: the heads bold, what breaks and its fix stand out
            String t = l.strip();
            System.out.println(!l.startsWith(" ") ? Ansi.bold(l) : t.startsWith("breaks at") ? Ansi.yellow(l)
                    : t.startsWith("fix:") ? Ansi.cyan(l) : t.startsWith("call ") || t.startsWith("entry ") ? Ansi.dim(l) : l);
        }
        section("vocabulary");
        Path worksheet = dir.resolve(CentralVocabulary.WORKSHEET);
        System.out.println("Vocabulary: " + vocab.conflicts().size() + (vocab.conflicts().size() == 1 ? " conflict" : " conflicts")
                + " between keys nobody decided yet");
        vocab.conflicts().forEach(c -> System.out.println("  " + c));
        vocab.summary().stream().filter(l -> l.startsWith("to decide:") || l.startsWith("decisions to fix") || l.startsWith("  "))
                .forEach(l -> System.out.println("  " + l.strip()));
        if (vocabulary || Files.exists(worksheet)) {
            System.out.println("worksheet: " + worksheet + " (uncomment a `# decide:` line per key, then run `logast fleet " + name + "`)");
            System.out.println("company:   " + dir.resolve(CentralVocabulary.COMPANY) + " (" + vocab.terms()
                    + (vocab.terms() == 1 ? " term" : " terms") + ", generated; every member extends it)");
        } else {
            System.out.println("  `logast fleet " + name + " --vocabulary` writes the worksheet " + worksheet
                    + " to decide them in; from then on every `logast fleet " + name + "` keeps it and company.yaml current");
        }
        if (agreed != null) {
            System.out.println(agreed);
        }
        section("next");
        if (next.isEmpty()) {
            System.out.println("  nothing left: the members follow the company policy and nothing high is open");
        } else {
            for (int i = 0; i < next.size(); i++) {
                System.out.println("  " + Ansi.cyan((i + 1) + ".") + " " + next.get(i));
            }
        }
        // #149: a file:// URL, clickable in a terminal; --open shows it in the browser
        System.out.println();
        System.out.println("report: " + page.toAbsolutePath().toUri());
        if (openPage) {
            openInBrowser(page);
        }
        return 0;
    }

    /** #225: a section of the overview, headed as the phases are. */
    static void section(String title) {
        String head = "━━ " + title + " ";
        System.out.println();
        System.out.println(Ansi.bold(head + "━".repeat(Math.max(3, Math.min(Out.width(), 100) - head.length()))));
    }

    /** The system's opener (xdg-open, open, start); a failure is one warning, never the command's. */
    static void openInBrowser(Path page) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        List<String> cmd = os.contains("mac") ? List.of("open", page.toString())
                : os.contains("win") ? List.of("cmd", "/c", "start", "", page.toString()) : List.of("xdg-open", page.toString());
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            Out.warn("fleet: could not open the page (" + e.getMessage() + "); open " + page.toUri());
        }
    }

    static int open(FleetReport.Service s) {
        return s.severity().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** "just now", "12 min ago", "5 h ago", "3 days ago". */
    static String age(String created, Instant now) {
        try {
            Duration d = Duration.between(Instant.parse(created), now);
            if (d.toMinutes() < 1) {
                return "just now";
            }
            if (d.toMinutes() < 60) {
                return d.toMinutes() + " min ago";
            }
            return d.toHours() < 48 ? d.toHours() + " h ago" : d.toDays() + " days ago";
        } catch (RuntimeException e) {
            return created;
        }
    }

    /** What the overview says of a member without a snapshot: #225: a repo without Java never has one. */
    static String noSnapshot(String fleet, Path root, boolean added) {
        boolean noJava = root != null && Files.isDirectory(root)
                && dev.logast.export.RepoExport.roots(root).stream().noneMatch(x -> x.kind().equals("java"));
        return noJava ? "no findings: no Java to scan (its facts come with the knowledge base)"
                : added ? "no snapshot yet: `logast fleet " + fleet + " --refresh` scans it" : "no snapshot yet: run `logast scan` in " + root;
    }
}
