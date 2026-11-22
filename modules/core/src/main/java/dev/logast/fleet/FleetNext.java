package dev.logast.fleet;

import dev.logast.CentralVocabulary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * #149: what to do next with a fleet, most basic first, each step with the exact command or file (in backticks): the
 * end of {@code logast fleet <name>} and the top of fleet.html, so nothing has to be remembered.
 */
public final class FleetNext {
    private FleetNext() {
    }

    public static List<String> steps(String name, Path dir, Map<String, Path> members, Map<String, Path> joined,
                                     Map<String, NamedFleet.Snapshot> snaps, List<CompanyPolicy.Suggestion> suggestions,
                                     FleetLinks.Result links, CentralVocabulary.Result vocab) {
        List<String> out = new ArrayList<>();
        Path company = dir.resolve(CentralVocabulary.COMPANY);
        List<String> missing = members.keySet().stream().filter(m -> !snaps.containsKey(m)).sorted().toList();
        if (!missing.isEmpty()) {
            out.add("Scan " + count(missing.size(), "member") + " without a snapshot (" + names(missing) + "): `logast fleet "
                    + name + " --refresh`");
        }
        List<CompanyPolicy.Suggestion> agreed = suggestions.stream().filter(CompanyPolicy.Suggestion::unanimous).toList();
        if (!agreed.isEmpty()) {
            out.add("Make " + count(agreed.size(), "setting") + " every member uses company policy ("
                    + String.join(", ", agreed.stream().map(s -> s.path() + ": " + s.value()).toList())
                    + "): uncomment them in `" + company + "`");
        }
        List<CompanyPolicy.Suggestion> differ = suggestions.stream().filter(s -> !s.unanimous()).toList();
        if (!differ.isEmpty()) {
            out.add("Decide " + count(differ.size(), "setting") + " the members disagree on ("
                    + String.join(", ", differ.stream().map(CompanyPolicy.Suggestion::path).toList()) + "): in `" + company + "`");
        }
        List<String> untidy = new ArrayList<>();
        joined.forEach((svc, root) -> {
            if (Files.isDirectory(root) && !FleetTidy.plan(root, company).isEmpty()) {
                untidy.add(svc);
            }
        });
        if (!untidy.isEmpty()) {
            out.add("Remove what company.yaml already sets from " + count(untidy.size(), "member") + " (" + names(untidy)
                    + "): `logast fleet tidy " + name + "`");
        }
        if (vocab != null && !vocab.conflicts().isEmpty()) {
            boolean sheet = Files.exists(dir.resolve(CentralVocabulary.WORKSHEET));
            out.add("Decide " + count(vocab.conflicts().size(), "vocabulary conflict") + ": " + (sheet
                    ? "in `" + dir.resolve(CentralVocabulary.WORKSHEET) + "`, then `logast fleet " + name + "`"
                    : "`logast fleet " + name + " --vocabulary` writes the worksheet to decide them in"));
        }
        if (links != null) {
            Set<String> fixAt = new TreeSet<>();
            long breaks = 0;
            for (FleetLinks.Trace t : links.trace()) {
                if (t.breaks().isEmpty()) {
                    continue;
                }
                breaks++;
                if (t.breaks().contains("caller")) {
                    fixAt.add(t.caller());
                }
                if (t.breaks().contains("callee")) {
                    if (t.callee() != null) {
                        fixAt.add(t.callee());
                    } else {
                        t.candidates().stream().filter(FleetLinks.Candidate::breaks).forEach(c -> fixAt.add(c.service()));
                    }
                }
            }
            if (breaks > 0) {
                out.add("Close " + count((int) breaks, "trace break") + " between services: `logast apply .` in "
                        + names(List.copyOf(fixAt)) + " (the Trace table says which side drops it)");
            }
        }
        List<NamedFleet.Snapshot> high = snaps.values().stream()
                .filter(s -> s.report().severity().getOrDefault("high", 0) > 0)
                .sorted(Comparator.comparingDouble(s -> s.report().score())).toList();
        if (!high.isEmpty()) {
            int total = high.stream().mapToInt(s -> s.report().severity().get("high")).sum();
            out.add("Fix " + count(total, "high finding") + ": `logast apply .` in " + names(high.stream()
                    .map(s -> s.service() + " (" + s.report().severity().get("high") + ")").toList()) + ", lowest score first");
        }
        return out;
    }

    private static String count(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    /** "a, b, c" or "a, b, c and 4 more". */
    private static String names(List<String> names) {
        return names.size() <= 5 ? String.join(", ", names)
                : String.join(", ", names.subList(0, 5)) + " and " + (names.size() - 5) + " more";
    }
}
