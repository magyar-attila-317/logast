package dev.logast.kb;

import dev.logast.facts.Node;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * #166: Key Vault secrets ({@code secret:<vault>/<object>}, from Terraform and from Helm's SecretProviderClasses) with
 * who consumes them: the workloads that mount or read them, and through {@code deployed-as} the services whose code runs
 * there; optionally only those that expire within a number of days.
 */
public final class Secrets {
    private Secrets() {
    }

    public static String list(KnowledgeBase kb, String text, String env, Integer expiringDays, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        // #247: a secret is said by the repo that makes it (Terraform: its expiry) and by the charts that mount it (no
        // expiry); per secret and environment, the fact that has the expiry stands for it, else the first
        java.util.Map<String, KnowledgeBase.Fact<Node>> secrets = new java.util.LinkedHashMap<>();
        for (KnowledgeBase.Fact<Node> f : kb.nodes()) {
            Node n = f.fact();
            if (n.kind().equals("secret")) {
                secrets.merge(n.id() + "|" + n.env(), f, (had, now) -> had.fact().attrs().get("expiration_date") == null
                        && now.fact().attrs().get("expiration_date") != null ? now : had);
            }
        }
        int count = 0;
        for (KnowledgeBase.Fact<Node> f : secrets.values()) {
            Node n = f.fact();
            if (env != null && n.env() != null && !env.equals(n.env()) || text != null && !text.isBlank() && !n.id().contains(text)) {
                continue;
            }
            Object exp = n.attrs().get("expiration_date");
            LocalDate expires = null;
            if (exp != null) {
                try {
                    expires = OffsetDateTime.parse(exp.toString()).toLocalDate();
                } catch (DateTimeParseException e) {
                    try {
                        expires = LocalDate.parse(exp.toString().substring(0, Math.min(10, exp.toString().length())));
                    } catch (DateTimeParseException ignored) {
                        // not a date: shown as it is
                    }
                }
            }
            if (expiringDays != null && (expires == null || expires.isAfter(today.plusDays(expiringDays)))) {
                continue;
            }
            count++;
            sb.append("- ").append(n.id()).append(n.env() == null ? "" : " [" + n.env() + "]")
                    .append(exp == null ? "" : ", expires " + exp + (expires != null && !expires.isAfter(today) ? " (EXPIRED)" : ""))
                    .append(n.attrs().get("content_type") == null ? "" : ", " + n.attrs().get("content_type"))
                    .append(n.attrs().get("tags") == null ? "" : ", tags " + n.attrs().get("tags"))
                    .append("  (").append(KnowledgeBase.cite(n.evidence(), f.header())).append(")\n");
            List<String> consumers = new ArrayList<>();
            for (var e : kb.incoming(n.id())) {
                if (!e.fact().kind().equals("uses-secret") || env != null && e.fact().env() != null && !env.equals(e.fact().env())) {
                    continue;
                }
                String workload = e.fact().from();
                List<String> services = new ArrayList<>();
                for (var d : kb.incoming(workload)) {
                    if (d.fact().kind().equals("deployed-as") && (e.fact().env() == null || e.fact().env().equals(d.fact().env()))) {
                        services.add(d.fact().from());
                    }
                }
                consumers.add(workload + (e.fact().env() == null ? "" : " [" + e.fact().env() + "]")
                        + (services.isEmpty() ? "" : " (code: " + String.join(", ", services) + ")"));
            }
            if (consumers.isEmpty()) {
                sb.append("    no workload mounts or reads it in the facts\n");
            } else {
                consumers.stream().distinct().forEach(c -> sb.append("    used by ").append(c).append('\n'));
            }
        }
        return count == 0 ? "No secret" + (expiringDays == null ? "" : " expires within " + expiringDays + " days")
                + (env == null ? "" : " in " + env) + "." : count + " secret(s):\n" + sb;
    }
}
