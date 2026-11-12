package dev.logast.apply;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportFixerTest {
    private static final Map<String, String> SUPPORT = ImportFixer.learn(List.of("import com.shop.logging.MdcScope;\n"));

    @Test
    void addsMissingAndRemovesUnusedImports() {
        String src = """
                package com.shop.x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.slf4j.MDC;

                class A {
                    private static final Logger log = LoggerFactory.getLogger(A.class);
                    void a(String id) {
                        try (var mdc = MdcScope.of("orderId", id)) {
                            log.info("Order placed");
                        }
                    }
                }
                """;
        String fixed = ImportFixer.fix(src, SUPPORT);
        assertThat(fixed).contains("import com.shop.logging.MdcScope;").doesNotContain("import org.slf4j.MDC;")
                .contains("import org.slf4j.Logger;");
    }

    @Test
    void leavesOtherLoggersAndJavadocMentionsAlone() {
        String src = """
                package com.shop.x;

                import java.util.logging.Logger;
                import org.slf4j.MDC;

                /** Clears {@link MDC} on purpose. */
                class B {
                    private static final Logger log = Logger.getLogger("b");
                }
                """;
        String fixed = ImportFixer.fix(src, SUPPORT);
        assertThat(fixed).doesNotContain("import org.slf4j.Logger;").contains("import org.slf4j.MDC;");
    }

    @Test
    void samePackageNeedsNoImport() {
        String src = "package com.shop.logging;\n\nclass C {\n    void c() { try (var m = MdcScope.of(\"a\", 1)) { } }\n}\n";
        assertThat(ImportFixer.fix(src, SUPPORT)).doesNotContain("import com.shop.logging.MdcScope;");
    }
}
