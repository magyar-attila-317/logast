package dev.logast;

import dev.logast.orders.WorkOrder;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #58 e: formatting that does not change the code does not change the orders. Order ids are anchored to code (#43);
 * the e2e test covers CRLF ({@code crlfFilesKeepTheirLineEndings}) and a line added above every finding
 * ({@code orderIdsSurviveShiftedLines}). Here: the fixture indented with tabs instead of spaces.
 */
class FormattingVariantsTest {
    private static final Pattern INDENT = Pattern.compile("(?m)^((?: {4})+)");

    @Test
    void tabIndentationKeepsEveryOrder(@TempDir Path dir) throws IOException {
        Path root = Fixtures.root();
        Path copy = dir.resolve("sample-app");
        int indented = 0;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).filter(p -> !root.relativize(p).startsWith("build")).toList()) {
                Path dst = copy.resolve(root.relativize(p).toString());
                Files.createDirectories(dst.getParent());
                String text = Files.readString(p);
                if (p.toString().endsWith(".java")) {
                    Matcher m = INDENT.matcher(text);
                    StringBuilder sb = new StringBuilder();
                    while (m.find()) {
                        m.appendReplacement(sb, "\t".repeat(m.group(1).length() / 4));
                        indented++;
                    }
                    text = m.appendTail(sb).toString();
                }
                Files.writeString(dst, text);
            }
        }
        assertThat(indented).as("lines re-indented with tabs").isGreaterThan(500);

        List<String> spaces = fingerprint(Rules.scan(Fixtures.analysis()).orders());
        List<String> tabs = fingerprint(Rules.scan(Analysis.run(copy, Fixtures.classpath(), Fixtures.policy()).withTraffic(Fixtures.traffic())).orders());
        assertThat(spaces).as("the fixture has orders").hasSizeGreaterThan(100);
        assertThat(tabs).as("id, rule and position of every order, indented with tabs").isEqualTo(spaces);
    }

    private static List<String> fingerprint(List<WorkOrder> orders) {
        return orders.stream().map(o -> o.id() + " " + o.rule() + " " + o.file() + ":" + o.line()).sorted().toList();
    }
}
