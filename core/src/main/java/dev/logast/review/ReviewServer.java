package dev.logast.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.logast.apply.Applier;
import dev.logast.apply.Fill;
import dev.logast.apply.FillValidator;
import dev.logast.orders.CompactFormat;
import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@code logast review}: a local page where a human fills NEEDS TEXT orders, dry-runs and applies them. Bound to
 * 127.0.0.1; every API call needs the random token from the printed URL, so other sites in the browser cannot
 * drive it.
 */
public final class ReviewServer {
    private final Path root;
    private final Supplier<Function<Path, ScanResult>> scanners;
    private final Policy policy;
    private final String token;
    private HttpServer server;
    private ScanResult current;

    public ReviewServer(Path root, Supplier<Function<Path, ScanResult>> scanners, Policy policy) {
        this.root = root.toAbsolutePath().normalize();
        this.scanners = scanners;
        this.policy = policy;
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        this.token = HexFormat.of().formatHex(b);
    }

    /** Starts on a free port; returns the URL to open. */
    public String start(int port) throws IOException {
        current = scanners.get().apply(root);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/?t=" + token;
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (!token.equals(query(ex, "t"))) {
                send(ex, 403, "text/plain", "missing or wrong token");
                return;
            }
            switch (path) {
                case "/" -> send(ex, 200, "text/html; charset=utf-8", page());
                case "/api/orders" -> send(ex, 200, "application/json", OrderJson.MAPPER.writeValueAsString(orders()));
                case "/api/apply" -> {
                    if (!"POST".equals(ex.getRequestMethod())) {
                        send(ex, 405, "text/plain", "POST only");
                        return;
                    }
                    JsonNode body = OrderJson.MAPPER.readTree(ex.getRequestBody());
                    Map<String, Fill> fills = OrderJson.MAPPER.convertValue(body.path("fills"), new TypeReference<LinkedHashMap<String, Fill>>() {
                    });
                    boolean write = body.path("write").asBoolean(false);
                    Applier.Result r = new Applier(scanners.get(), new FillValidator(policy))
                            .apply(root, new Applier.Selection(body.path("auto").asBoolean(false), Set.of(), fills), write);
                    if (write) {
                        current = scanners.get().apply(root);
                    }
                    send(ex, 200, "application/json", OrderJson.MAPPER.writeValueAsString(r));
                }
                default -> send(ex, 404, "text/plain", "not found");
            }
        } catch (RuntimeException e) {
            send(ex, 500, "text/plain", String.valueOf(e.getMessage()));
        }
    }

    private Map<String, Object> orders() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WorkOrder o : current.orders()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id());
            m.put("rule", o.rule());
            m.put("fixes", o.fixes());
            m.put("severity", o.severity());
            m.put("kind", o.reportOnly() ? "report" : o.slots().isEmpty() ? "auto" : "text");
            m.put("where", o.file() + ":" + o.line());
            m.put("method", o.method());
            m.put("title", o.title());
            m.put("suggestion", o.suggestion());
            m.put("path", o.paths().isEmpty() ? null : o.paths().getFirst());
            m.put("code", o.context() == null ? null : o.context().code());
            m.put("vars", o.context() == null || o.context().argsSlot() == null ? List.of() : o.context().varsInScope().keySet());
            m.put("slots", o.slots());
            m.put("preview", CompactFormat.previewLines(Path.of(current.root()), o));
            out.add(m);
        }
        return Map.of("root", current.root(), "terms", policy.terms(), "orders", out);
    }

    private static String page() throws IOException {
        try (InputStream in = ReviewServer.class.getResourceAsStream("/review/index.html")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return null;
        }
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(name)) {
                return kv.substring(i + 1);
            }
        }
        return null;
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
