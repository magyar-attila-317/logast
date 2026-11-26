package dev.logast;

import dev.logast.orders.Edit;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G1/G2: suffix keys say what the value is. Two values the vocabulary maps to one key keep their own names (and stay);
 * a getter chain is named by its owner; a transformation by its source; an exception's class name is errorType.
 */
class SuffixKeyNamingTest {
    @Test
    void keysNameTheirValues(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), """
                vocabulary:
                  device:
                    mdc: { key: deviceId, sources: { params: [deviceId, tokenDeviceId, bodyDeviceId] } }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("User.java"), "package x;\n\npublic record User(String id) {\n    public String getId() { return id; }\n}\n");
        Files.writeString(src.resolve("Token.java"), """
                package x;

                public record Token(String id, User user) {
                    public String getId() { return id; }
                    public User getUser() { return user; }
                }
                """);
        Files.writeString(src.resolve("Login.java"), """
                package x;

                import java.util.Locale;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Login {
                    private static final Logger log = LoggerFactory.getLogger(Login.class);

                    void check(String tokenDeviceId, String bodyDeviceId, Token token, String kind) {
                        log.warn("Device mismatch tokenDeviceId={} bodyDeviceId={}", tokenDeviceId, bodyDeviceId);
                        log.info("token {} used for user id {}", token.getId(), token.getUser().getId());
                        log.info("Kind checked: {}", kind.toLowerCase(Locale.ROOT));
                        try {
                            run();
                        } catch (IllegalStateException e) {
                            log.warn("Check failed: {}", e.getClass().getSimpleName());
                        }
                    }

                    void run() {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        // a line in prose becomes NEEDS TEXT: its keys are the slot's; a mechanical rewrite writes them in the edit
        String edits = String.join("\n", orders.stream().filter(o -> "Login.check".equals(o.method()))
                .flatMap(o -> java.util.stream.Stream.concat(o.edits().stream().map(Edit::text).filter(t -> t != null),
                        o.slots().values().stream().flatMap(sl -> sl.keys().stream().map(k -> k + "={}")))).toList());
        assertThat(edits).as("both device ids, by their own names").contains("tokenDeviceId={}", "bodyDeviceId={}")
                .as("the user's id").contains("userId={}").doesNotContain("[id={}")
                .as("the source of a transformation").contains("kind={}").doesNotContain("toLowerCase={}")
                .as("an exception's class").contains("errorType={}").doesNotContain("simpleName={}");
    }

    /**
     * #335 (a dry run on a real service): a ternary of the exception's message and a text is a reason, not
     * "eInstanceofJwtExceptionEGetMessage…"; a helper that presents a value is named after the value (describeHost → host).
     */
    @Test
    void ternariesAndPresentingHelpersAreNamedAfterTheValue(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Pool.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Pool {
                    private static final Logger log = LoggerFactory.getLogger(Pool.class);

                    void connect(String url, String region, String fallbackRegion, boolean primary) {
                        log.info("pool targets {}", describeHost(url));
                        log.info("pool region {}", primary ? region : fallbackRegion);
                        try {
                            run();
                        } catch (RuntimeException e) {
                            log.warn("Rejecting session {}", e instanceof IllegalStateException ? e.getMessage() : "malformed session id claim");
                        }
                    }

                    String describeHost(String url) {
                        return url;
                    }

                    void run() {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        String edits = String.join("\n", orders.stream().filter(o -> "Pool.connect".equals(o.method()))
                .flatMap(o -> java.util.stream.Stream.concat(o.edits().stream().map(Edit::text).filter(t -> t != null),
                        o.slots().values().stream().flatMap(sl -> sl.keys().stream().map(k -> k + "={}")))).toList());
        assertThat(edits).contains("host={}", "reason={}").as("two values of a ternary: a plain value").contains("value={}")
                .doesNotContain("describeHost={}", "eInstanceof", "primaryRegion");
    }

    /**
     * #323 H1/H3 (a large service's dry run): two inline names that end in the same word ("source item={}", "clone
     * item={}") keep apart; the values a line compares (the authority a token is bound to and the one required, the
     * token's and the body's device, the user and the token type) all stay.
     */
    @Test
    void valuesALineComparesAllStay(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), """
                vocabulary:
                  device:
                    mdc: { key: deviceId, sources: { params: [deviceId, tokenDeviceId], types: { x.Request: "deviceId()" } } }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Request.java"), "package x;\n\npublic record Request(String deviceId) {\n}\n");
        Files.writeString(src.resolve("Jwt.java"), "package x;\n\npublic class Jwt {\n    String extractTokenType(String jwt) { return jwt; }\n}\n");
        Files.writeString(src.resolve("Guard.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Guard {
                    private static final Logger log = LoggerFactory.getLogger(Guard.class);
                    private final Jwt jwtUtil = new Jwt();

                    void copy(String sourceId, String cloneId) {
                        try {
                            run();
                        } catch (IllegalStateException e) {
                            log.error("Copy failed (source item={}, clone item={}): {}", sourceId, cloneId, e.getMessage());
                        }
                    }

                    void authorize(String tokenAuthority, String requiredAuthority) {
                        log.warn("Token bound to {} but {} was required", tokenAuthority, requiredAuthority);
                    }

                    void device(String tokenDeviceId, Request request) {
                        log.warn("Device mismatch tokenDeviceId={} bodyDeviceId={}", tokenDeviceId, request.deviceId());
                    }

                    @org.springframework.web.bind.annotation.PostMapping("/devices/{tokenDeviceId}/check")
                    public void check(@org.springframework.web.bind.annotation.PathVariable String tokenDeviceId,
                                      @org.springframework.web.bind.annotation.RequestBody Request request) {
                        log.warn("Device mismatch tokenDeviceId={} bodyDeviceId={}", tokenDeviceId, request.deviceId());
                    }

                    void login(String username, String jwt) {
                        log.info("Authenticated user {} with token type {}", username, jwtUtil.extractTokenType(jwt));
                    }

                    void run() {
                    }
                }
                """);
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null))).orders();
        java.util.Map<String, String> byMethod = new java.util.TreeMap<>();
        for (WorkOrder o : orders) {
            if (o.method() != null && o.method().startsWith("Guard.")) {
                String t = java.util.stream.Stream.concat(o.edits().stream().map(Edit::text).filter(x -> x != null),
                        o.slots().values().stream().flatMap(sl -> sl.keys().stream().map(k -> k + "={}"))).reduce("", (a, b) -> a + "\n" + b);
                byMethod.merge(o.method(), o.rule() + ": " + t, (a, b) -> a + "\n" + b);
            }
        }
        String copy = byMethod.getOrDefault("Guard.copy", "");
        assertThat(copy).as(copy).doesNotContain("item={}, item={}").containsPattern("source\\w*=\\{}").containsPattern("clone\\w*=\\{}");
        assertThat(byMethod.getOrDefault("Guard.authorize", "")).as(byMethod.toString()).doesNotContain("[requiredAuthority={}]\")");
        String device = byMethod.getOrDefault("Guard.device", "");
        assertThat(device).as(device).doesNotContain("[tokenDeviceId={}]\"");
        String login = byMethod.getOrDefault("Guard.login", "");
        assertThat(login).as(login).doesNotContain("\"Authenticated user {}\"");
    }

    /** #323 H3, applied: once tokenDeviceId is in MDC the body's device id still stays in the line it is compared in. */
    @Test
    void aComparedValueSurvivesTheApply(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("logast.yaml"), """
                packs: [logging]
                vocabulary:
                  device:
                    mdc: { key: deviceId, sources: { params: [deviceId], types: { x.Request: "deviceId()" } } }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Request.java"), "package x;\n\npublic record Request(String deviceId) {\n}\n");
        Files.writeString(src.resolve("Devices.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class Devices {
                    private static final Logger log = LoggerFactory.getLogger(Devices.class);

                    @PostMapping("/devices/{deviceId}/check")
                    public void check(@PathVariable String deviceId, @RequestBody Request request) {
                        String tokenDeviceId = deviceId.trim();
                        if (!tokenDeviceId.equals(request.deviceId())) {
                            log.warn("Device mismatch tokenDeviceId={} bodyDeviceId={}", tokenDeviceId, request.deviceId());
                        }
                    }
                }
                """);
        java.util.function.Function<Path, dev.logast.orders.ScanResult> scan = p -> Logast.scan(p, Fixtures.classpath(), Logast.policy(p, null));
        dev.logast.apply.Applier applier = new dev.logast.apply.Applier(scan, new dev.logast.apply.FillValidator(Logast.policy(dir, null)));
        applier.apply(dir, new dev.logast.apply.Applier.Selection(true, java.util.Set.of(), java.util.Map.of()), true);
        String after = Files.readString(src.resolve("Devices.java"));
        assertThat(after).as(after).contains("request.deviceId()").containsPattern("bodyDeviceId=\\{}");
    }

    /** #326 L6: a generic accessor (getUuid, getId) is named after what it is read from: phoneUuid, itemUuid. */
    @Test
    void aGenericAccessorTakesItsOwnersName() throws Exception {
        org.openrewrite.java.tree.J.CompilationUnit cu = org.openrewrite.java.JavaParser.fromJavaVersion().build()
                .parse("class A { Object f(P phone, P item) { return java.util.List.of(phone.getUuid(), item.getUuid(), this.getId()); } "
                        + "Object getId() { return null; } }").findFirst().map(org.openrewrite.java.tree.J.CompilationUnit.class::cast).orElseThrow();
        List<String> keys = new java.util.ArrayList<>();
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public org.openrewrite.java.tree.J.MethodInvocation visitMethodInvocation(org.openrewrite.java.tree.J.MethodInvocation mi, Integer p) {
                if (mi.getSimpleName().startsWith("get")) {
                    keys.add(dev.logast.rules.DomainValues.derivedKey(mi));
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(cu, 0);
        assertThat(keys).containsExactly("phoneUuid", "itemUuid", "id");
    }
}
