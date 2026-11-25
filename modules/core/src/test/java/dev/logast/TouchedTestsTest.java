package dev.logast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #289: after an apply, the tests that use a changed class (per module) and the changed classes no test checks the logs of. */
class TouchedTestsTest {
    @Test
    void testsOfChangedClassesAndUncheckedLogLines(@TempDir Path root) throws Exception {
        write(root, "src/test/java/com/example/OrderServiceTest.java", """
                package com.example;
                import ch.qos.logback.core.read.ListAppender;
                class OrderServiceTest { OrderService s; ListAppender<Object> logs; }
                """);
        write(root, "src/test/java/com/example/RefundsTest.java", """
                package com.example;
                class RefundsTest { RefundService s; }
                """);
        write(root, "billing/src/test/java/com/example/billing/InvoiceTest.java", """
                package com.example.billing;
                class InvoiceTest { Invoices i; }
                """);
        write(root, "src/test/java/com/example/UnrelatedTest.java", """
                package com.example;
                class UnrelatedTest { OrderServices notTheSameName; }
                """);
        TouchedTests t = TouchedTests.of(root, List.of("src/main/java/com/example/OrderService.java",
                "src/main/java/com/example/RefundService.java", "billing/src/main/java/com/example/billing/Invoices.java",
                "src/main/java/com/example/Ledger.java", "src/main/java/com/example/logging/MdcScope.java",
                "src/main/resources/application.yml"), "/example/logging/");
        assertThat(t.tests()).isEqualTo(Map.of("test", List.of("com.example.OrderServiceTest", "com.example.RefundsTest"),
                ":billing:test", List.of("com.example.billing.InvoiceTest")));
        assertThat(t.command()).isEqualTo("./gradlew :billing:test --tests com.example.billing.InvoiceTest "
                + "test --tests com.example.OrderServiceTest --tests com.example.RefundsTest");
        assertThat(t.unchecked()).as("OrderServiceTest captures logs; the support class is logast's own")
                .containsExactly("billing/src/main/java/com/example/billing/Invoices.java",
                        "src/main/java/com/example/Ledger.java", "src/main/java/com/example/RefundService.java");
    }

    private static void write(Path root, String rel, String text) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }
}
