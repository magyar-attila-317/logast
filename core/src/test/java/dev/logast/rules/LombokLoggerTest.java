package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.Fixtures;
import dev.logast.orders.Edit;
import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** With Lombok on the classpath a class without a logger gets @Slf4j, not a hand-written field. */
class LombokLoggerTest {
    @Test
    void addsSlf4jAfterTheJavadocAndBeforeOtherAnnotations(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        String original = """
                package x;

                import org.springframework.stereotype.Repository;

                /** Coupons. */
                @Repository
                public class CouponRepository {
                    void close() {
                    }
                }
                """;
        Files.writeString(src.resolve("CouponRepository.java"), original);
        Analysis a = Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null));
        var owner = a.index.classes().stream().filter(c -> c.simpleName().equals("CouponRepository")).findFirst().orElseThrow();

        JavaEdits.LoggerPlan plan = JavaEdits.logger(owner, "log", true);
        assertThat(plan.name()).isEqualTo("log");
        String result = apply(original, plan.edits());
        assertThat(result).contains("import lombok.extern.slf4j.Slf4j;", "/** Coupons. */\n@Slf4j\n@Repository\npublic class CouponRepository {")
                .doesNotContain("LoggerFactory");

        // a custom logger name is not what @Slf4j generates: keep the field
        assertThat(apply(original, JavaEdits.logger(owner, "logger", true).edits())).contains("private static final Logger logger");
    }

    private static String apply(String text, List<Edit> edits) {
        StringBuilder sb = new StringBuilder(text);
        edits.stream().sorted(Comparator.comparingInt(Edit::start).reversed()).forEach(e -> sb.replace(e.start(), e.end(), e.text()));
        return sb.toString();
    }
}
