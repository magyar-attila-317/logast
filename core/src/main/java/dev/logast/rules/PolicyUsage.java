package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.parse.SourceFileInfo;
import dev.logast.policy.Policy;
import dev.logast.policy.PolicyValidator;

import java.util.*;

/**
 * #56: the second pass of the policy check, once the code is known: names in the policy and in ignore comments that
 * match nothing, so they silently do nothing. Load-time checks (unknown keys, packs, rule ids, regexes, …) are
 * {@link PolicyValidator}'s.
 */
public final class PolicyUsage {
    private PolicyUsage() {
    }

    /** Warnings, each "where: what; the fix". Empty: everything the policy names exists. */
    public static List<String> warnings(Analysis a) {
        List<String> out = new ArrayList<>();
        Set<String> classes = new HashSet<>();
        Map<String, Set<String>> methods = new HashMap<>();
        for (ClassInfo c : a.index.classes()) {
            classes.add(c.fqn());
        }
        for (MethodInfo m : a.index.methods()) {
            methods.computeIfAbsent(m.owner().fqn(), k -> new HashSet<>()).add(m.name());
        }

        // audit.emitters: com.x.AuditService or com.x.ActivityLogger#log
        List<String> emitters = a.policy.audit().emitters();
        for (int i = 0; i < emitters.size(); i++) {
            String e = emitters.get(i);
            String type = e.contains("#") ? e.substring(0, e.indexOf('#')) : e;
            String method = e.contains("#") ? e.substring(e.indexOf('#') + 1) : null;
            String where = "logast.yaml: audit.emitters[" + i + "]";
            if (!classes.contains(type) && !a.project.probe().hasClass(type)) {
                out.add(where + ": " + type + " is not in the code or on the classpath, so no call counts as an audit "
                        + "record; check the package" + similar(type, classes));
            } else if (method != null && classes.contains(type) && !methods.getOrDefault(type, Set.of()).contains(method)) {
                out.add(where + ": " + type + " has no method " + method + "; it has "
                        + String.join(", ", new TreeSet<>(methods.getOrDefault(type, Set.of()))));
            }
        }

        // vocabulary …mdc.sources.types: the types a business id is read from. A shared layer (the central company.yaml,
        // #113) lists the types of every service: one this service lacks is expected, so only its own file's types count
        Set<String> own = a.project.root() == null ? null : dev.logast.policy.PolicyLoader.ownSourceTypes(a.project.root());
        for (var term : a.policy.vocabulary().entrySet()) {
            Policy.TermMdc mdc = term.getValue().mdc();
            if (mdc == null || mdc.sources() == null) {
                continue;
            }
            for (String type : mdc.sources().types().keySet()) {
                if ((own == null || own.contains(type)) && !classes.contains(type) && !a.project.probe().hasClass(type)) {
                    out.add("logast.yaml: vocabulary." + term.getKey() + ".mdc.sources.types: " + type
                            + " is not in the code or on the classpath, so " + mdc.key() + " is never read from it"
                            + similar(type, classes));
                }
            }
        }

        // logast:ignore RULE with a rule id that does not exist
        for (SourceFileInfo f : a.project.files()) {
            for (String hit : Suppressions.unknownIds(f.text())) {
                String id = hit.substring(hit.indexOf(": ") + 2);
                out.add(f.relPath() + ":" + hit.substring(0, hit.indexOf(':')) + ": logast:ignore " + id
                        + " names no rule, so it silences nothing" + PolicyValidator.didYouMean(id, Rules.all()));
            }
        }
        return out;
    }

    /** "; did you mean com.x.Y?" for a class of the code with the same simple name, else "". */
    private static String similar(String fqn, Set<String> classes) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        return classes.stream().filter(c -> c.endsWith("." + simple)).findFirst().map(c -> "; did you mean " + c + "?").orElse("");
    }
}
