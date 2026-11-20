package dev.logast.kb;

import dev.logast.export.HelmManifests;
import dev.logast.facts.Edge;
import dev.logast.facts.Header;
import dev.logast.facts.Node;
import dev.logast.facts.Provenance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * #161: which deployments run which service's code. A service ({@code service:<name>}, with the {@code image} its build
 * pushes when the build says so) and a workload ({@code deployment:<name>} or a CronJob's {@code job:…}, which
 * {@code deploys} {@code image:<repository>}) are linked by the strongest rule that holds: the same image repository,
 * the same name, or the image's last path segment equal to the service's name. A workload two services claim by the
 * same rule is left unlinked and reported as ambiguous.
 */
final class Identity {
    record Link(String service, String workload, String env, String via, double confidence, KnowledgeBase.Fact<Node> workloadFact) {
    }

    private Identity() {
    }

    static final Header HEADER = Header.of("logast-identity", null, "derived", null);

    /** The links, and (as text) the workloads more than one service claims. */
    static Map.Entry<List<Link>, List<String>> links(List<KnowledgeBase.Fact<Node>> nodes, Map<String, List<KnowledgeBase.Fact<Edge>>> out,
                                                     Knowledge knowledge) {
        Map<String, Node> services = new LinkedHashMap<>();
        for (KnowledgeBase.Fact<Node> n : nodes) {
            if (n.fact().kind().equals("service")) {
                services.putIfAbsent(n.fact().id(), n.fact());
            }
        }
        List<Link> links = new ArrayList<>();
        List<String> ambiguous = new ArrayList<>();
        for (KnowledgeBase.Fact<Node> w : nodes) {
            Node wn = w.fact();
            boolean workload = wn.kind().equals("deployment") || wn.kind().equals("job") && "cronjob".equals(wn.attrs().get("entrypoint"));
            if (!workload) {
                continue;
            }
            List<String> images = out.getOrDefault(wn.id(), List.of()).stream()
                    .filter(e -> e.fact().kind().equals("deploys") && Objects.equals(e.fact().env(), wn.env()))
                    .map(e -> e.fact().to().substring("image:".length())).toList();
            String name = wn.id().substring(wn.id().lastIndexOf(':') + 1);
            Map<String, List<String>> byRule = new LinkedHashMap<>();
            for (Node s : services.values()) {
                String svc = s.id().substring("service:".length());
                String built = s.attrs().get("image") == null ? null : HelmManifests.splitImage(String.valueOf(s.attrs().get("image")))[0];
                String rule = null;
                if (built != null && images.contains(built)) {
                    rule = "image";
                } else if (svc.equals(knowledge.serviceFor(name))) {
                    rule = "alias";   // #163: knowledge.yml says the workload's name is another name of the service
                } else if (name.equals(svc) || name.equals(s.attrs().get("application"))) {
                    rule = "name";
                } else if (images.stream().anyMatch(i -> i.substring(i.lastIndexOf('/') + 1).equals(svc))) {
                    rule = "image-name";
                }
                if (rule != null) {
                    byRule.computeIfAbsent(rule, k -> new ArrayList<>()).add(s.id());
                }
            }
            for (String rule : List.of("image", "alias", "name", "image-name")) {
                List<String> claim = byRule.get(rule);
                if (claim == null) {
                    continue;
                }
                if (claim.size() == 1) {
                    double confidence = switch (rule) {
                        case "image" -> 0.95;
                        case "alias" -> 0.9;
                        case "name" -> 0.8;
                        default -> 0.7;
                    };
                    links.add(new Link(claim.getFirst(), wn.id(), wn.env(), rule, confidence, w));
                } else {
                    ambiguous.add(wn.id() + (wn.env() == null ? "" : " [" + wn.env() + "]") + ": " + String.join(", ", claim) + " (by " + rule + ")");
                }
                break;
            }
        }
        return Map.entry(links, ambiguous);
    }

    static Edge edge(Link l) {
        return new Edge(l.service(), "deployed-as", l.workload(), l.env(), Map.of(), Provenance.INFERRED, l.confidence(),
                "identity:" + l.via(), null, l.workloadFact().fact().evidence());
    }

    static Map<String, Integer> counts(List<Link> links) {
        Map<String, Integer> out = new HashMap<>();
        links.forEach(l -> out.merge(l.via(), 1, Integer::sum));
        return out;
    }
}
