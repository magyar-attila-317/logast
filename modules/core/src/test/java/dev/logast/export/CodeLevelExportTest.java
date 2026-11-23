package dev.logast.export;

import dev.logast.Fixtures;
import dev.logast.Logast;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #255: {@code export --code-level}: classes with their whole supertype chain (library types included), interface size
 * with inherited methods, methods, the calls the call graph resolved, which entrypoints reach each class; and on every
 * export, what protects each outgoing call and where that is configured.
 */
class CodeLevelExportTest {
    @TempDir
    Path tmp;

    private Path shop() throws IOException {
        Path root = tmp.resolve("shop");
        Path src = Files.createDirectories(root.resolve("src/main/java/com/example/shop"));
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        Files.writeString(src.resolve("Lookup.java"), """
                package com.example.shop;

                public interface Lookup {
                    String find(String id);
                }
                """);
        Files.writeString(src.resolve("Catalog.java"), """
                package com.example.shop;

                public interface Catalog extends Lookup {
                    void refresh();
                }
                """);
        Files.writeString(src.resolve("BaseClient.java"), """
                package com.example.shop;

                public abstract class BaseClient {
                    protected abstract String name();
                }
                """);
        Files.writeString(src.resolve("ShopError.java"), """
                package com.example.shop;

                public class ShopError extends RuntimeException {
                    public ShopError(String message) {
                        super(message);
                    }
                }
                """);
        // built on its own: Spring Boot's timeouts do not reach it, and it sets none
        Files.writeString(src.resolve("StockClient.java"), """
                package com.example.shop;

                import org.springframework.resilience.annotation.Retryable;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestClient;

                @Component
                public class StockClient extends BaseClient implements Catalog {
                    private final RestClient rest = RestClient.create("http://stock");

                    @Override
                    @Retryable
                    public String find(String id) {
                        return rest.get().uri("/stock/{id}", id).retrieve().body(String.class);
                    }

                    @Override
                    public void refresh() {
                    }

                    @Override
                    protected String name() {
                        return "stock";
                    }
                }
                """);
        // its own request factory with timeouts, and a concurrency limit
        Files.writeString(src.resolve("PriceClient.java"), """
                package com.example.shop;

                import org.springframework.http.client.SimpleClientHttpRequestFactory;
                import org.springframework.resilience.annotation.ConcurrencyLimit;
                import org.springframework.stereotype.Component;
                import org.springframework.web.client.RestClient;

                @Component
                public class PriceClient {
                    private final RestClient rest = RestClient.builder().baseUrl("http://prices").requestFactory(factory()).build();

                    private static SimpleClientHttpRequestFactory factory() {
                        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
                        f.setConnectTimeout(2000);
                        f.setReadTimeout(5000);
                        return f;
                    }

                    @ConcurrencyLimit(4)
                    public String price(String id) {
                        return rest.get().uri("/prices/{id}", id).retrieve().body(String.class);
                    }
                }
                """);
        Files.writeString(src.resolve("StockController.java"), """
                package com.example.shop;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class StockController {
                    private final Catalog catalog;
                    private final PriceClient prices;

                    public StockController(Catalog catalog, PriceClient prices) {
                        this.catalog = catalog;
                        this.prices = prices;
                    }

                    @GetMapping("/stock/{id}")
                    public String stock(@PathVariable String id) {
                        if (id.isBlank()) {
                            throw new ShopError("no id");
                        }
                        return catalog.find(id) + prices.price(id);
                    }
                }
                """);
        return root;
    }

    private List<String> export(Path root, boolean codeLevel) throws IOException {
        StringWriter out = new StringWriter();
        Export.java(root, Fixtures.classpath(), Logast.policy(root, null), "shop", "test", null, out, null, true, codeLevel);
        return out.toString().lines().toList();
    }

    private static String one(List<String> facts, String fragment) {
        List<String> hits = facts.stream().filter(l -> l.contains(fragment)).toList();
        assertThat(hits).as(fragment + " in\n" + String.join("\n", facts)).hasSize(1);
        return hits.getFirst();
    }

    @Test
    void classesMethodsCallsAndReach() throws IOException {
        List<String> facts = export(shop(), true);
        assertThat(one(facts, "\"id\":\"class:shop:com.example.shop.StockClient\"")).as("the chain to Object; every interface, transitively")
                .contains("\"kind\":\"class\"", "\"superChain\":[\"com.example.shop.BaseClient\",\"java.lang.Object\"]",
                        "\"interfaces\":[\"com.example.shop.Catalog\",\"com.example.shop.Lookup\"]", "\"methodCount\":3",
                        "\"file\":\"src/main/java/com/example/shop/StockClient.java\",\"line\":8");
        assertThat(one(facts, "\"id\":\"class:shop:com.example.shop.ShopError\"")).as("library supertypes, by name")
                .contains("\"superChain\":[\"java.lang.RuntimeException\",\"java.lang.Exception\",\"java.lang.Throwable\",\"java.lang.Object\"]",
                        "\"interfaces\":[\"java.io.Serializable\"]");
        assertThat(one(facts, "\"id\":\"class:shop:com.example.shop.Catalog\"")).as("an interface's size counts what it inherits")
                .contains("\"kind\":\"interface\"", "\"methodCount\":1", "\"inheritedMethodCount\":1");
        assertThat(one(facts, "\"id\":\"class:shop:com.example.shop.BaseClient\"")).contains("\"abstract\":true");
        assertThat(facts).anyMatch(l -> l.contains("\"from\":\"class:shop:com.example.shop.StockClient\",\"kind\":\"extends\",\"to\":\"class:shop:com.example.shop.BaseClient\""))
                .anyMatch(l -> l.contains("\"from\":\"class:shop:com.example.shop.StockClient\",\"kind\":\"implements\",\"to\":\"class:shop:com.example.shop.Catalog\""))
                .anyMatch(l -> l.contains("\"from\":\"class:shop:com.example.shop.Catalog\",\"kind\":\"extends\",\"to\":\"class:shop:com.example.shop.Lookup\""));
        assertThat(one(facts, "\"id\":\"method:shop:com.example.shop.StockController#stock(java.lang.String)\""))
                .contains("\"line\":18", "\"endLine\":23");
        assertThat(facts).as("the interface call dispatches to its one implementation, cited at the call site")
                .anyMatch(l -> l.contains("\"from\":\"method:shop:com.example.shop.StockController#stock(java.lang.String)\",\"kind\":\"calls\","
                        + "\"to\":\"method:shop:com.example.shop.StockClient#find(java.lang.String)\"") && l.contains("\"line\":22"));
        assertThat(one(facts, "\"id\":\"class:shop:com.example.shop.PriceClient\"")).as("how many entrypoints' runs reach the class")
                .contains("\"reachedByCount\":1");
        assertThat(one(facts, "\"id\":\"class:shop:com.example.shop.Lookup\"")).as("nothing runs an interface's code")
                .contains("\"reachedByCount\":0");

        assertThat(export(shop(), false)).as("only with --code-level").noneMatch(l -> l.contains("\"kind\":\"class\"") || l.contains("\"kind\":\"method\""));
    }

    @Test
    void whatProtectsEachOutgoingCall() throws IOException {
        Path root = shop();
        Files.writeString(root.resolve("src/main/java/com/example/shop/ShopConfig.java"), """
                package com.example.shop;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.resilience.annotation.EnableResilientMethods;

                @Configuration
                @EnableResilientMethods
                public class ShopConfig {
                }
                """);
        List<String> facts = export(root, false);
        assertThat(one(facts, "\"id\":\"call:shop:http GET http://stock/stock/{id}\"")).as("a retry on the method; a client built without a timeout")
                .contains("\"retry\":\"yes\"", "\"retryFrom\":\"src/main/java/com/example/shop/StockClient.java:12\"",
                        "\"timeout\":\"no\"", "\"timeoutFrom\":\"src/main/java/com/example/shop/StockClient.java:9\"",
                        "\"circuitBreaker\":\"not known\"", "\"bulkhead\":\"not known\"");
        assertThat(one(facts, "\"id\":\"call:shop:http GET http://prices/prices/{id}\"")).as("its own request factory sets timeouts; a concurrency limit")
                .contains("\"timeout\":\"yes\"", "\"timeoutFrom\":\"src/main/java/com/example/shop/PriceClient.java:10\"",
                        "\"bulkhead\":\"yes\"", "\"retry\":\"not known\"");
    }

    /** The same annotations without @EnableResilientMethods: Spring does not process them, so they protect nothing. */
    @Test
    void anAnnotationNothingEnablesProtectsNothing() throws IOException {
        List<String> facts = export(shop(), false);
        assertThat(one(facts, "\"id\":\"call:shop:http GET http://stock/stock/{id}\"")).as("@Retryable without its switch")
                .contains("\"retry\":\"not known\"", "\"retryInactive\":\"src/main/java/com/example/shop/StockClient.java:12: Spring does not process it here")
                .doesNotContain("\"retryFrom\"");
        assertThat(one(facts, "\"id\":\"call:shop:http GET http://prices/prices/{id}\"")).contains("\"bulkhead\":\"not known\"", "\"bulkheadInactive\"");
    }
}
