package dev.logast.export;

import dev.logast.facts.Edge;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** #160: a Helm repo's rendered manifests per environment as facts. */
class HelmExtractorTest {
    @TempDir
    Path tmp;

    private static final class Collect implements FactSink {
        final List<Node> nodes = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();

        @Override
        public void node(Node n) {
            nodes.add(n);
        }

        @Override
        public void edge(Edge e) {
            edges.add(e);
        }

        Node node(String id, String env) {
            return nodes.stream().filter(n -> n.id().equals(id) && Objects.equals(n.env(), env)).findFirst().orElseThrow(
                    () -> new AssertionError("no " + id + " in " + env + ": " + nodes.stream().map(Node::id).toList()));
        }

        List<String> edges(String env) {
            return edges.stream().filter(e -> Objects.equals(e.env(), env)).map(e -> e.from() + " -" + e.kind() + "-> " + e.to()).toList();
        }
    }

    private static String manifests(String env, String tag, String host, String cron) {
        return """
                ---
                # Source: platform/charts/orders/templates/secretproviderclass.yaml
                apiVersion: secrets-store.csi.x-k8s.io/v1
                kind: SecretProviderClass
                metadata:
                  name: orders-kv
                spec:
                  provider: azure
                  parameters:
                    keyvaultName: kv-orders-ENV
                    objects: |
                      array:
                        - |
                          objectName: db-password
                          objectType: secret
                        - |
                          objectName: api-key
                          objectType: secret
                          objectAlias: partner-key
                  secretObjects:
                    - secretName: orders-secrets
                      type: Opaque
                      data:
                        - objectName: db-password
                          key: db-password
                        - objectName: partner-key
                          key: partner-key
                ---
                # Source: platform/charts/orders/templates/deployment.yaml
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: orders
                  labels:
                    app.kubernetes.io/instance: platform
                    helm.sh/chart: orders-1.0.0
                spec:
                  replicas: 2
                  template:
                    metadata:
                      labels:
                        app: orders
                        tier: backend
                    spec:
                      containers:
                        - name: orders
                          image: registry.example.com/shop/orders:TAG
                          env:
                            - name: SHIPPING_URL
                              value: http://shipping:8080
                            - name: SPRING_DATASOURCE_PASSWORD
                              valueFrom:
                                secretKeyRef:
                                  name: orders-secrets
                                  key: db-password
                            - name: PARTNER_KEY
                              valueFrom:
                                secretKeyRef:
                                  name: orders-secrets
                                  key: partner-key
                          envFrom:
                            - configMapRef:
                                name: orders-config
                      volumes:
                        - name: kv
                          csi:
                            driver: secrets-store.csi.k8s.io
                            volumeAttributes:
                              secretProviderClass: orders-kv
                ---
                # Source: platform/charts/orders/templates/service.yaml
                apiVersion: v1
                kind: Service
                metadata:
                  name: orders
                spec:
                  selector:
                    app: orders
                  ports:
                    - name: http
                      port: 8080
                ---
                # Source: platform/charts/orders/templates/ingress.yaml
                apiVersion: networking.k8s.io/v1
                kind: Ingress
                metadata:
                  name: orders
                spec:
                  rules:
                    - host: HOST
                      http:
                        paths:
                          - path: /api
                            backend:
                              service:
                                name: orders
                                port:
                                  number: 8080
                ---
                # Source: platform/charts/orders/templates/cronjob.yaml
                apiVersion: batch/v1
                kind: CronJob
                metadata:
                  name: orders-cleanup
                  labels:
                    app.kubernetes.io/instance: platform
                spec:
                  schedule: "CRON"
                  timeZone: Europe/Berlin
                  jobTemplate:
                    spec:
                      template:
                        spec:
                          containers:
                            - name: cleanup
                              image: registry.example.com/shop/orders:TAG
                ---
                # Source: kafka-topics/templates/topics.yaml
                apiVersion: kafka.strimzi.io/v1beta2
                kind: KafkaTopic
                metadata:
                  name: orders-placed
                  labels:
                    strimzi.io/cluster: main
                spec:
                  topicName: orders.placed
                  partitions: 6
                  replicas: 3
                  config:
                    retention.ms: 604800000
                ---
                # Source: kafka-topics/templates/users.yaml
                apiVersion: kafka.strimzi.io/v1beta2
                kind: KafkaUser
                metadata:
                  name: billing
                spec:
                  authorization:
                    type: simple
                    acls:
                      - resource: {type: topic, name: orders.placed, patternType: literal}
                        operations: [Read, Describe]
                      - resource: {type: group, name: billing}
                        operations: [Read]
                ---
                # Source: platform/templates/dashboards.yaml
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: orders-dashboards
                  labels:
                    grafana_dashboard: "1"
                  annotations:
                    grafana_folder: Shop
                data:
                  orders.json: |
                    {"title": "Orders", "panels": [{"targets": [{"expr": "sum(rate(orders_placed_total{env=\\"ENV\\"}[5m])) by (status)"}, {"expr": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))"}]}]}
                """.replace("ENV", env).replace("TAG", tag).replace("HOST", host).replace("CRON", cron);
    }

    private Path repo() throws IOException {
        Path root = tmp.resolve("deploy");
        Files.createDirectories(root.resolve("charts/platform/charts/orders/templates"));
        Files.createDirectories(root.resolve("charts/kafka-topics/templates"));
        Files.createDirectories(root.resolve("values/prod"));
        Files.writeString(root.resolve("helmfile.yaml"), """
                environments:
                  dev:
                    values: [values/dev/common.yaml]
                  prod:
                    values: [values/prod/common.yaml]

                releases:
                  - name: platform
                    chart: ./charts/platform
                """);
        Files.writeString(root.resolve("charts/platform/Chart.yaml"), "apiVersion: v2\nname: platform\nversion: 1.0.0\n");
        Files.writeString(root.resolve("charts/platform/charts/orders/Chart.yaml"), "apiVersion: v2\nname: orders\nversion: 1.0.0\n");
        Files.writeString(root.resolve("charts/platform/charts/orders/templates/deployment.yaml"), "kind: Deployment\n");
        Files.writeString(root.resolve("charts/kafka-topics/Chart.yaml"), "apiVersion: v2\nname: kafka-topics\nversion: 0.1.0\n");
        Files.writeString(root.resolve("charts/kafka-topics/templates/topics.yaml"), "{{- range .Values.topics }}\nname: {{ .name }}\n{{- end }}\n");
        Files.writeString(root.resolve("values/prod/topics.yaml"), "topics:\n  - name: orders.placed\n    partitions: 6\n");
        Path rendered = Files.createDirectories(root.resolve("rendered"));
        Files.writeString(rendered.resolve("dev.yaml"), manifests("dev", "dev-41", "orders.dev.example.com", "*/30 * * * *"));
        Files.writeString(rendered.resolve("prod.yaml"), manifests("prod", "1.4.2", "orders.example.com", "0 3 * * *"));
        return root;
    }

    @Test
    void eachEnvironmentsManifestsBecomeFacts() throws IOException {
        Path root = repo();
        Collect c = new Collect();
        HelmExtractor hx = new HelmExtractor(List.of(), List.of(), root.resolve("rendered"));
        hx.extract(root, c);
        assertThat(hx.problems()).isEmpty();

        Node prod = c.node("deployment:orders", "prod");
        assertThat(prod.attrs()).containsEntry("replicas", 2).containsEntry("release", "platform").containsEntry("chart", "orders-1.0.0")
                .containsEntry("envFrom", List.of("configmap:orders-config"));
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) prod.attrs().get("env");
        assertThat(env).containsEntry("SHIPPING_URL", "http://shipping:8080")
                .as("a Key Vault object synced into a Secret, read as an env var").containsEntry("SPRING_DATASOURCE_PASSWORD", "secret:kv-orders-prod/db-password")
                .as("through its alias").containsEntry("PARTNER_KEY", "secret:kv-orders-prod/api-key");
        assertThat(prod.evidence().file()).as("the subchart's template in the repo").isEqualTo("charts/platform/charts/orders/templates/deployment.yaml");

        assertThat(c.edges("prod")).contains(
                "deployment:orders -deploys-> image:registry.example.com/shop/orders",
                "deployment:orders -uses-secret-> secret:kv-orders-prod/db-password",
                "k8s-service:orders -routes-> deployment:orders",
                "ingress:orders -routes-> k8s-service:orders",
                "job:platform:orders-cleanup -deploys-> image:registry.example.com/shop/orders",
                "kafka-user:billing -may-read-> topic:orders.placed",
                "dashboard:Orders -queries-> metric:orders_placed_total",
                "dashboard:Orders -queries-> metric:http_server_requests_seconds_bucket");
        assertThat(c.edges("prod")).as("a group ACL is no topic").noneMatch(e -> e.contains("topic:billing"));
        assertThat(c.edges.stream().filter(e -> e.kind().equals("deploys") && "prod".equals(e.env())).findFirst().orElseThrow().attrs())
                .containsEntry("tag", "1.4.2");
        assertThat(c.edges("dev")).contains("deployment:orders -uses-secret-> secret:kv-orders-dev/db-password");

        assertThat(c.node("ingress:orders", "dev").attrs()).containsEntry("hosts", List.of("orders.dev.example.com"));
        assertThat(c.node("job:platform:orders-cleanup", "prod").attrs()).as("a CronJob is a job with a schedule runs_on reads")
                .containsEntry("schedule", "0 3 * * *").containsEntry("scheduleFormat", "unix").containsEntry("zone", "Europe/Berlin");
        assertThat(c.node("job:platform:orders-cleanup", "dev").attrs()).containsEntry("schedule", "*/30 * * * *");

        Node topic = c.node("topic:orders.placed", "prod");
        assertThat(topic.attrs()).containsEntry("partitions", 6).containsEntry("cluster", "main");
        assertThat(topic.evidence().file()).as("where people edit topics: the values file").isEqualTo("values/prod/topics.yaml");
        assertThat(topic.evidence().line()).isEqualTo(2);
        assertThat(c.node("dashboard:Orders", "prod").attrs()).containsEntry("folder", "Shop");
        assertThat(c.edges("prod")).noneMatch(e -> e.contains("metric:by") || e.contains("metric:histogram_quantile"));
    }

    @Test
    void helpers() {
        assertThat(HelmExtractor.environments("""
                environments:
                  dev:
                    values: [a.yaml]
                  prod: {}

                releases:
                  - name: x
                """)).containsExactly("dev", "prod");
        assertThat(HelmManifests.splitImage("registry.example.com:5000/shop/orders:1.2")).containsExactly("registry.example.com:5000/shop/orders", "1.2");
        assertThat(HelmManifests.splitImage("orders")).containsExactly("orders", null);
        assertThat(HelmManifests.splitImage("orders@sha256:abc")).containsExactly("orders", "sha256:abc");
    }

    @Test
    void withoutHelmfileOrRenderedManifestsItSaysWhatIsMissing() throws IOException {
        Path root = repo();
        HelmExtractor hx = new HelmExtractor(List.of("staging"), List.of(), root.resolve("rendered"));
        hx.extract(root, new Collect());
        assertThat(hx.problems()).singleElement().asString().startsWith("staging: no ");
    }
}
