package dev.logast.orders;

import dev.logast.rules.RuleLabels;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * #148: findings as GitHub Actions workflow commands ({@code ::error file=…,line=…::…}): the runner turns them into
 * annotations on the pull request's diff on every plan, without code scanning (which SARIF upload needs on private
 * repos). Paths are relative to the scanned folder, as in SARIF: scan the repository root.
 */
public final class GithubAnnotations {
    private GithubAnnotations() {
    }

    /** One line per finding, then one per gap of an incomplete analysis. */
    public static String of(List<WorkOrder> orders, List<String> gaps) {
        StringBuilder out = new StringBuilder();
        for (WorkOrder o : orders) {
            String level = switch (o.severity()) {
                case "high" -> "error";
                case "medium" -> "warning";
                default -> "notice";
            };
            String label = RuleLabels.of(o.rule());
            String title = "logast " + o.rule() + (label == null ? "" : " (" + label + ")");
            String text = (o.title() == null ? o.rule() : o.title())
                    + (o.suggestion() == null ? "" : "\n" + o.suggestion())
                    + "\n[" + o.id() + (o.reportOnly() ? ", report-only" : o.slots().isEmpty() ? ", fix: logast apply" : ", fix: needs text") + "]";
            out.append("::").append(level).append(" file=").append(property(o.file())).append(",line=").append(Math.max(o.line(), 1))
                    .append(",title=").append(property(title)).append("::").append(data(text)).append('\n');
        }
        gaps.forEach(g -> out.append("::warning title=logast::").append(data("analysis incomplete: " + g)).append('\n'));
        return out.toString();
    }

    /** The markdown for {@code $GITHUB_STEP_SUMMARY}: counts by severity, then by rule with its plain label. */
    public static String summary(List<WorkOrder> orders, List<String> gaps) {
        Map<String, Integer> bySeverity = new TreeMap<>();
        Map<String, Integer> byRule = new TreeMap<>();
        orders.forEach(o -> {
            bySeverity.merge(o.severity(), 1, Integer::sum);
            byRule.merge(o.rule(), 1, Integer::sum);
        });
        StringBuilder md = new StringBuilder("### logast\n\n");
        if (orders.isEmpty()) {
            md.append("No findings.\n");
        } else {
            md.append("**").append(orders.size()).append(orders.size() == 1 ? " finding" : " findings").append("**: ")
                    .append(bySeverity.getOrDefault("high", 0)).append(" high, ").append(bySeverity.getOrDefault("medium", 0))
                    .append(" medium, ").append(bySeverity.getOrDefault("low", 0)).append(" low. `logast apply .` fixes the AUTO ones.\n\n")
                    .append("| rule | what | findings |\n|---|---|---:|\n");
            byRule.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> md.append("| `").append(e.getKey()).append("` | ")
                            .append(String.valueOf(RuleLabels.of(e.getKey())).replace("|", "\\|")).append(" | ").append(e.getValue()).append(" |\n"));
        }
        if (!gaps.isEmpty()) {
            md.append("\n> **Analysis incomplete**: ").append(String.join("; ", gaps)).append('\n');
        }
        return md.toString();
    }

    /** A workflow command's message: %, CR and LF escaped (the runner's own escaping). */
    static String data(String s) {
        return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A");
    }

    /** A workflow command's property value: also ':' and ','. */
    static String property(String s) {
        return data(s).replace(":", "%3A").replace(",", "%2C");
    }
}
