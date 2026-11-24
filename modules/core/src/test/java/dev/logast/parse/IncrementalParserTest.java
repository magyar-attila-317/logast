package dev.logast.parse;

import dev.logast.Analysis;
import dev.logast.Fixtures;
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
 * #308: a round re-parses only the files whose text changed (and new ones), and its result is the full parse's; a
 * change of a file's API, a removed file or many changed files make the caller parse in full.
 */
class IncrementalParserTest {
    private static final String ORDERS = """
            package x;

            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;

            public class Orders {
                private static final Logger log = LoggerFactory.getLogger(Orders.class);
                private final Payments payments = new Payments();

                public void place(String orderId) {
                    payments.charge(orderId);
                    log.info("Order placed: " + orderId);
                }
            }
            """;
    private static final String PAYMENTS = """
            package x;

            public class Payments {
                public void charge(String orderId) {
                    System.out.println(orderId);
                }
            }
            """;

    @Test
    void aBodyEditReparsesOneFileAndScansLikeAFullParse(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Orders.java"), ORDERS);
        Files.writeString(src.resolve("Payments.java"), PAYMENTS);
        List<Path> cp = Fixtures.classpath();
        IncrementalParser inc = new IncrementalParser(cp);
        ParsedProject first = new ProjectParser().parse(dir, cp);

        // a body edit, as an apply round makes it
        Files.writeString(src.resolve("Orders.java"), ORDERS.replace("log.info(\"Order placed: \" + orderId);",
                "log.info(\"Order placed [orderId={}]\", orderId);"));
        ParsedProject reparsed = inc.reparse(first);
        assertThat(reparsed).as(inc.lastNote).isNotNull();
        assertThat(inc.lastNote).isEqualTo("re-parsed 1 of 2 files");
        assertThat(reparsed.byRelPath().get("src/main/java/x/Payments.java").cu())
                .as("the unchanged file keeps its tree").isSameAs(first.byRelPath().get("src/main/java/x/Payments.java").cu());

        ParsedProject full = new ProjectParser().parse(dir, cp);
        assertThat(ids(reparsed)).as("the same findings as a full parse of the same tree").isEqualTo(ids(full));
    }

    @Test
    void anApiChangeOrARemovedFileParsesInFull(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Orders.java"), ORDERS);
        Files.writeString(src.resolve("Payments.java"), PAYMENTS);
        List<Path> cp = Fixtures.classpath();
        ParsedProject first = new ProjectParser().parse(dir, cp);

        IncrementalParser inc = new IncrementalParser(cp);
        Files.writeString(src.resolve("Payments.java"), PAYMENTS.replace("System.out.println(orderId);",
                "System.out.println(orderId);\n    }\n\n    public void refund(String orderId) {\n        System.out.println(orderId);"));
        assertThat(inc.reparse(first)).as("a public method added: other files may use it").isNull();
        assertThat(inc.lastNote).contains("API").contains("Payments.java");

        Files.writeString(src.resolve("Payments.java"), PAYMENTS.replace("System.out.println(orderId);",
                "System.out.println(orderId);\n    }\n\n    private void audit(String orderId) {\n        System.out.println(orderId);"));
        IncrementalParser again = new IncrementalParser(cp);
        assertThat(again.reparse(first)).as("a private method is no API").isNotNull();

        Files.writeString(src.resolve("Payments.java"), PAYMENTS);
        Files.writeString(src.resolve("Refunds.java"), "package x;\n\npublic class Refunds {\n}\n");
        IncrementalParser added = new IncrementalParser(cp);
        ParsedProject withNew = added.reparse(first);
        assertThat(withNew).as("a new file is parsed with the changed ones").isNotNull();
        assertThat(withNew.byRelPath()).containsKey("src/main/java/x/Refunds.java");
        Files.delete(src.resolve("Refunds.java"));
        assertThat(new IncrementalParser(cp).reparse(withNew)).as("a removed file").isNull();
    }

    @Test
    void fullParsesCanBeForced() {
        String before = System.getProperty("logast.parse");
        try {
            System.setProperty("logast.parse", "full");
            assertThat(IncrementalParser.enabled()).isFalse();
            System.setProperty("logast.parse", "incremental");
            assertThat(IncrementalParser.enabled()).isTrue();
        } finally {
            if (before == null) {
                System.clearProperty("logast.parse");
            } else {
                System.setProperty("logast.parse", before);
            }
        }
    }

    private static List<String> ids(ParsedProject p) throws Exception {
        Policy policy = Policy.resolve(p.root(), null);
        return Rules.scan(Analysis.of(p, policy)).orders().stream().map(WorkOrder::id).sorted().toList();
    }
}
