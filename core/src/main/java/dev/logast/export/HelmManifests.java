package dev.logast.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #160: rendered Kubernetes manifests ({@code helm template} / {@code helmfile template} output) of one environment as
 * facts: workloads with their image and env vars, CronJobs as jobs with a schedule, Services and the workloads they
 * select, Ingress hosts, Key Vault objects mounted through a SecretProviderClass, Strimzi topics and users, Grafana
 * dashboards and the metrics their queries use.
 */
public final class HelmManifests {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern SOURCE = Pattern.compile("(?m)^# Source: (\\S+)");
    private static final Set<String> WORKLOADS = Set.of("Deployment", "StatefulSet", "DaemonSet", "Job");
    /** A metric name in a PromQL query: a word with an underscore or colon, not followed by "(" (a function). */
    private static final Pattern METRIC = Pattern.compile("(?<![\\w\\[\"'.$])([a-zA-Z_:][a-zA-Z0-9_:]*_[a-zA-Z0-9_:]+)(?=\\s*(?:\\{|\\[|\\)|$|\\s|,))");

    /** One rendered document and the chart template it came from. */
    record Doc(JsonNode body, String source) {
    }

    private final String env;
    private final Function<String, Evidence> evidenceOf;
    private final Function<String, Evidence> valuesLine;

    /**
     * @param env        the environment the manifests are of
     * @param evidenceOf a {@code # Source:} path ({@code chart/templates/x.yaml}) → evidence in the repo
     * @param valuesLine a name (a topic) → the values file line that names it, else null
     */
    public HelmManifests(String env, Function<String, Evidence> evidenceOf, Function<String, Evidence> valuesLine) {
        this.env = env;
        this.evidenceOf = evidenceOf;
        this.valuesLine = valuesLine;
    }

    static List<Doc> docs(String rendered) throws IOException {
        List<Doc> out = new ArrayList<>();
        for (String part : rendered.split("(?m)^---\\s*$")) {
            if (part.isBlank()) {
                continue;
            }
            Matcher m = SOURCE.matcher(part);
            String source = m.find() ? m.group(1) : null;
            JsonNode body = YAML.readTree(part);
            if (body != null && body.isObject() && body.has("kind")) {
                out.add(new Doc(body, source));
            }
        }
        return out;
    }

    public void extract(String rendered, FactSink out) throws IOException {
        List<Doc> docs = docs(rendered);
        // what Secret / SecretProviderClass objects hand to pods: k8s secret name/key → vault/object
        Map<String, String> syncedSecrets = new LinkedHashMap<>();
        Map<String, List<String>> providerObjects = new LinkedHashMap<>();
        for (Doc d : docs) {
            if (d.body().path("kind").asText().equals("SecretProviderClass")) {
                secretProviderClass(d, syncedSecrets, providerObjects, out);
            }
        }
        Map<String, Map<String, String>> podLabels = new LinkedHashMap<>();
        for (Doc d : docs) {
            String kind = d.body().path("kind").asText();
            if (WORKLOADS.contains(kind)) {
                workload(d, syncedSecrets, providerObjects, podLabels, out);
            } else if (kind.equals("CronJob")) {
                cronJob(d, syncedSecrets, providerObjects, out);
            } else if (kind.equals("KafkaTopic")) {
                topic(d, out);
            } else if (kind.equals("KafkaUser")) {
                user(d, out);
            } else if (kind.equals("ConfigMap") && !d.body().path("metadata").path("labels").path("grafana_dashboard").isMissingNode()) {
                dashboards(d, out);
            }
        }
        for (Doc d : docs) {
            String kind = d.body().path("kind").asText();
            if (kind.equals("Service")) {
                service(d, podLabels, out);
            } else if (kind.equals("Ingress")) {
                ingress(d, out);
            }
        }
    }

    // ---- workloads ----------------------------------------------------------------------------------------------------

    private void workload(Doc d, Map<String, String> synced, Map<String, List<String>> objects,
                          Map<String, Map<String, String>> podLabels, FactSink out) {
        JsonNode b = d.body();
        String name = b.at("/metadata/name").asText();
        JsonNode pod = b.at("/spec/template");
        Map<String, Object> attrs = meta(b);
        attrs.put("workload", b.path("kind").asText());
        if (b.at("/spec/replicas").isNumber()) {
            attrs.put("replicas", b.at("/spec/replicas").asInt());
        }
        String id = "deployment:" + name;
        Evidence at = evidence(d);
        Map<String, String> labels = new TreeMap<>();
        pod.at("/metadata/labels").fields().forEachRemaining(e -> labels.put(e.getKey(), e.getValue().asText()));
        podLabels.put(id, labels);
        pod(id, pod.path("spec"), attrs, synced, objects, at, out);
        out.node(new Node(id, "deployment", env, attrs, at));
    }

    private void cronJob(Doc d, Map<String, String> synced, Map<String, List<String>> objects, FactSink out) {
        JsonNode b = d.body();
        String name = b.at("/metadata/name").asText();
        Map<String, Object> attrs = meta(b);
        attrs.put("schedule", b.at("/spec/schedule").asText());
        attrs.put("scheduleFormat", "unix");
        attrs.put("entrypoint", "cronjob");
        if (!b.at("/spec/timeZone").asText("").isBlank()) {
            attrs.put("zone", b.at("/spec/timeZone").asText());
        }
        if (b.at("/spec/suspend").asBoolean(false)) {
            attrs.put("suspended", true);
        }
        String release = String.valueOf(attrs.getOrDefault("release", "k8s"));
        String id = "job:" + release + ":" + name;
        Evidence at = evidence(d);
        pod(id, b.at("/spec/jobTemplate/spec/template/spec"), attrs, synced, objects, at, out);
        out.node(new Node(id, "job", env, attrs, at));
    }

    /** Images, env vars and mounted secrets of a pod spec: attrs on the workload, edges to images and secrets. */
    private void pod(String id, JsonNode spec, Map<String, Object> attrs, Map<String, String> synced,
                     Map<String, List<String>> objects, Evidence at, FactSink out) {
        Map<String, Object> envVars = new TreeMap<>();
        List<String> images = new ArrayList<>();
        Set<String> secrets = new LinkedHashSet<>();
        List<JsonNode> containers = new ArrayList<>();
        spec.path("initContainers").forEach(containers::add);
        spec.path("containers").forEach(containers::add);
        for (JsonNode c : containers) {
            String image = c.path("image").asText("");
            if (!image.isBlank()) {
                images.add(image);
                String[] rt = splitImage(image);
                out.edge(Edge.extracted(id, "deploys", "image:" + rt[0], rt[1] == null ? null : Map.of("tag", rt[1]), at).inEnv(env));
            }
            for (JsonNode e : c.path("env")) {
                String n = e.path("name").asText();
                if (e.has("value")) {
                    envVars.put(n, e.path("value").asText());
                } else if (e.at("/valueFrom/secretKeyRef").isObject()) {
                    String ref = e.at("/valueFrom/secretKeyRef/name").asText() + "/" + e.at("/valueFrom/secretKeyRef/key").asText();
                    String vault = synced.get(ref);
                    envVars.put(n, vault != null ? "secret:" + vault : "k8s-secret:" + ref);
                    if (vault != null) {
                        secrets.add(vault);
                    }
                } else if (e.at("/valueFrom/configMapKeyRef").isObject()) {
                    envVars.put(n, "configmap:" + e.at("/valueFrom/configMapKeyRef/name").asText() + "/" + e.at("/valueFrom/configMapKeyRef/key").asText());
                } else if (e.at("/valueFrom/fieldRef").isObject()) {
                    envVars.put(n, "field:" + e.at("/valueFrom/fieldRef/fieldPath").asText());
                }
            }
            List<String> from = new ArrayList<>();
            c.path("envFrom").forEach(f -> {
                if (f.has("configMapRef")) {
                    from.add("configmap:" + f.at("/configMapRef/name").asText());
                } else if (f.has("secretRef")) {
                    from.add("k8s-secret:" + f.at("/secretRef/name").asText());
                }
            });
            if (!from.isEmpty()) {
                attrs.put("envFrom", from);
            }
        }
        for (JsonNode v : spec.path("volumes")) {
            String spc = v.at("/csi/volumeAttributes/secretProviderClass").asText("");
            if (!spc.isBlank()) {
                secrets.addAll(objects.getOrDefault(spc, List.of()));
            }
        }
        for (String s : secrets) {
            out.edge(Edge.extracted(id, "uses-secret", "secret:" + s, null, at).inEnv(env));
        }
        if (!images.isEmpty()) {
            attrs.put("images", images);
        }
        if (!envVars.isEmpty()) {
            attrs.put("env", envVars);
        }
    }

    /** {@code registry/repo:tag} → [registry/repo, tag] ({@link dev.logast.facts.Images}). */
    public static String[] splitImage(String image) {
        return dev.logast.facts.Images.split(image);
    }

    // ---- secrets ------------------------------------------------------------------------------------------------------

    /**
     * Azure Key Vault's provider: {@code parameters.keyvaultName} and {@code parameters.objects} (a YAML string with
     * {@code array: [- objectName: …]}); {@code secretObjects} sync objects into a Kubernetes Secret, read as env vars.
     */
    private void secretProviderClass(Doc d, Map<String, String> synced, Map<String, List<String>> objects, FactSink out) throws IOException {
        JsonNode b = d.body();
        String name = b.at("/metadata/name").asText();
        JsonNode params = b.at("/spec/parameters");
        String vault = params.path("keyvaultName").asText(params.path("vaultName").asText("vault"));
        Map<String, String> aliasToObject = new LinkedHashMap<>();
        List<String> list = new ArrayList<>();
        String objectsText = params.path("objects").asText("");
        if (!objectsText.isBlank()) {
            JsonNode parsed = YAML.readTree(objectsText);
            for (JsonNode entry : parsed.path("array")) {
                JsonNode o = entry.isTextual() ? YAML.readTree(entry.asText()) : entry;
                String obj = o.path("objectName").asText("");
                if (obj.isBlank()) {
                    continue;
                }
                list.add(vault + "/" + obj);
                aliasToObject.put(o.path("objectAlias").asText(obj), obj);
                Evidence at = evidence(d);
                out.node(new Node("secret:" + vault + "/" + obj, "secret", env,
                        Map.of("vault", vault, "object", obj, "type", o.path("objectType").asText("secret")), at));
            }
        }
        objects.put(name, list);
        for (JsonNode so : b.at("/spec/secretObjects")) {
            String secretName = so.path("secretName").asText();
            for (JsonNode data : so.path("data")) {
                String obj = aliasToObject.getOrDefault(data.path("objectName").asText(), data.path("objectName").asText());
                synced.put(secretName + "/" + data.path("key").asText(), vault + "/" + obj);
            }
        }
    }

    // ---- services and ingress -----------------------------------------------------------------------------------------

    private void service(Doc d, Map<String, Map<String, String>> podLabels, FactSink out) {
        JsonNode b = d.body();
        String name = b.at("/metadata/name").asText();
        Map<String, Object> attrs = meta(b);
        List<String> ports = new ArrayList<>();
        b.at("/spec/ports").forEach(p -> ports.add(p.path("port").asText() + (p.has("name") ? "/" + p.path("name").asText() : "")));
        attrs.put("ports", ports);
        Map<String, String> selector = new TreeMap<>();
        b.at("/spec/selector").fields().forEachRemaining(e -> selector.put(e.getKey(), e.getValue().asText()));
        Evidence at = evidence(d);
        String id = "k8s-service:" + name;
        out.node(new Node(id, "k8s-service", env, attrs, at));
        if (!selector.isEmpty()) {
            podLabels.forEach((workload, labels) -> {
                if (labels.entrySet().containsAll(selector.entrySet())) {
                    out.edge(Edge.extracted(id, "routes", workload, null, at).inEnv(env));
                }
            });
        }
    }

    private void ingress(Doc d, FactSink out) {
        JsonNode b = d.body();
        String name = b.at("/metadata/name").asText();
        Map<String, Object> attrs = meta(b);
        List<String> hosts = new ArrayList<>();
        Evidence at = evidence(d);
        String id = "ingress:" + name;
        for (JsonNode rule : b.at("/spec/rules")) {
            String host = rule.path("host").asText("");
            if (!host.isBlank()) {
                hosts.add(host);
            }
            for (JsonNode p : rule.at("/http/paths")) {
                String svc = p.at("/backend/service/name").asText(p.at("/backend/serviceName").asText(""));
                if (!svc.isBlank()) {
                    out.edge(Edge.extracted(id, "routes", "k8s-service:" + svc,
                            Map.of("host", host, "path", p.path("path").asText("/")), at).inEnv(env));
                }
            }
        }
        attrs.put("hosts", hosts);
        out.node(new Node(id, "ingress", env, attrs, at));
    }

    // ---- Strimzi ------------------------------------------------------------------------------------------------------

    private void topic(Doc d, FactSink out) {
        JsonNode b = d.body();
        String name = b.at("/spec/topicName").asText(b.at("/metadata/name").asText());
        Map<String, Object> attrs = new TreeMap<>();
        if (b.at("/spec/partitions").isNumber()) {
            attrs.put("partitions", b.at("/spec/partitions").asInt());
        }
        if (b.at("/spec/replicas").isNumber()) {
            attrs.put("replicas", b.at("/spec/replicas").asInt());
        }
        Map<String, Object> config = new TreeMap<>();
        b.at("/spec/config").fields().forEachRemaining(e -> config.put(e.getKey(), e.getValue().asText()));
        if (!config.isEmpty()) {
            attrs.put("config", config);
        }
        String cluster = b.at("/metadata/labels").path("strimzi.io/cluster").asText("");
        if (!cluster.isBlank()) {
            attrs.put("cluster", cluster);
        }
        Evidence values = valuesLine.apply(name);   // where people edit topics: the values file, not the rendered object
        out.node(new Node("topic:" + name, "topic", env, attrs, values != null ? values : evidence(d)));
    }

    private void user(Doc d, FactSink out) {
        JsonNode b = d.body();
        String name = b.at("/metadata/name").asText();
        Evidence at = evidence(d);
        String id = "kafka-user:" + name;
        out.node(new Node(id, "kafka-user", env, meta(b), at));
        for (JsonNode acl : b.at("/spec/authorization/acls")) {
            JsonNode r = acl.path("resource");
            if (!r.path("type").asText().equals("topic")) {
                continue;
            }
            String topic = r.path("name").asText();
            String pattern = r.path("patternType").asText("literal");
            List<String> ops = new ArrayList<>();
            if (acl.has("operations")) {
                acl.path("operations").forEach(o -> ops.add(o.asText()));
            } else if (acl.has("operation")) {
                ops.add(acl.path("operation").asText());
            }
            for (String op : ops) {
                String kind = switch (op) {
                    case "Read" -> "may-read";
                    case "Write" -> "may-write";
                    case "All" -> "may-all";
                    default -> null;
                };
                if (kind != null) {
                    out.edge(Edge.extracted(id, kind, "topic:" + topic, pattern.equals("literal") ? null : Map.of("pattern", pattern), at).inEnv(env));
                }
            }
        }
    }

    // ---- Grafana ------------------------------------------------------------------------------------------------------

    private void dashboards(Doc d, FactSink out) throws IOException {
        JsonNode b = d.body();
        Evidence at = evidence(d);
        String folder = b.at("/metadata/annotations").path("grafana_folder").asText(null);
        for (Iterator<Map.Entry<String, JsonNode>> it = b.path("data").fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode dash;
            try {
                dash = new ObjectMapper().readTree(e.getValue().asText());
            } catch (IOException ex) {
                continue;
            }
            String title = dash.path("title").asText(e.getKey());
            Set<String> metrics = new java.util.TreeSet<>();
            collectExprs(dash, metrics);
            Map<String, Object> attrs = new TreeMap<>();
            attrs.put("file", e.getKey());
            if (folder != null) {
                attrs.put("folder", folder);
            }
            String id = "dashboard:" + title;
            out.node(new Node(id, "dashboard", env, attrs, at));
            for (String m : metrics) {
                out.edge(Edge.extracted(id, "queries", "metric:" + m, null, at).inEnv(env));
            }
        }
    }

    private static void collectExprs(JsonNode n, Set<String> metrics) {
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> {
                if ((e.getKey().equals("expr") || e.getKey().equals("query")) && e.getValue().isTextual()) {
                    Matcher m = METRIC.matcher(e.getValue().asText());
                    while (m.find()) {
                        String name = m.group(1);
                        if (!name.startsWith("__") && !Set.of("by", "without", "on", "ignoring").contains(name)) {
                            metrics.add(name);
                        }
                    }
                } else {
                    collectExprs(e.getValue(), metrics);
                }
            });
        } else if (n.isArray()) {
            n.forEach(x -> collectExprs(x, metrics));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    /** namespace, release and chart from the object's metadata and Helm's standard labels. */
    private static Map<String, Object> meta(JsonNode b) {
        Map<String, Object> attrs = new TreeMap<>();
        JsonNode md = b.path("metadata");
        if (!md.path("namespace").asText("").isBlank()) {
            attrs.put("namespace", md.path("namespace").asText());
        }
        JsonNode labels = md.path("labels");
        String release = labels.path("app.kubernetes.io/instance").asText(labels.path("release").asText(""));
        if (!release.isBlank()) {
            attrs.put("release", release);
        }
        String chart = labels.path("helm.sh/chart").asText(labels.path("chart").asText(""));
        if (!chart.isBlank()) {
            attrs.put("chart", chart);
        }
        return attrs;
    }

    private Evidence evidence(Doc d) {
        Evidence e = d.source() == null ? null : evidenceOf.apply(d.source());
        return e != null ? e : Evidence.at(d.source() != null ? d.source() : "rendered", null);
    }
}
