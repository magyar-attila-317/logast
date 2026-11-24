package dev.logast.apply;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #316: logging-only edits are not behavioural; a scope around code, a deleted statement or a moved read are. */
class ChangeClassifierTest {
    private static final String BEFORE = """
            package x;

            public class Payments {
                public void charge(String orderId, Ledger ledger) {
                    ledger.book(orderId);
                }

                public void refund(String orderId, Ledger ledger) {
                    if (orderId == null) {
                        throw new IllegalArgumentException("no order");
                    }
                    ledger.reverse(orderId);
                }
            }
            """;

    private final ChangeClassifier classifier = new ChangeClassifier(null);

    @Test
    void loggingOnlyEditsAreNotListed() {
        String after = BEFORE.replace("package x;\n", "package x;\n\nimport org.slf4j.Logger;\nimport org.slf4j.LoggerFactory;\n")
                .replace("public class Payments {\n", "@lombok.extern.slf4j.Slf4j\npublic class Payments {\n    private static final Logger log = LoggerFactory.getLogger(Payments.class);\n\n")
                .replace("        ledger.book(orderId);\n", "        ledger.book(orderId);\n        log.info(\"Order charged [orderId={}]\", orderId); // logast:wording\n");
        assertThat(classifier.compare("Payments.java", BEFORE, after)).isEmpty();
    }

    @Test
    void aScopeAroundCodeIsBehavioural() {
        String after = BEFORE.replace("        if (orderId == null) {\n            throw new IllegalArgumentException(\"no order\");\n        }\n        ledger.reverse(orderId);\n",
                "        try (var mdc = MdcScope.of(\"orderId\", orderId.strip())) {\n            if (orderId == null) {\n"
                        + "                throw new IllegalArgumentException(\"no order\");\n            }\n            ledger.reverse(orderId);\n        }\n");
        List<ChangeClassifier.Behavioural> found = classifier.compare("Payments.java", BEFORE, after);
        assertThat(found).singleElement().satisfies(b -> {
            assertThat(b.method()).isEqualTo("Payments.refund");
            assertThat(b.what()).isEqualTo("code changed besides logging");
            assertThat(b.line()).isEqualTo(8);
        });
    }
}
