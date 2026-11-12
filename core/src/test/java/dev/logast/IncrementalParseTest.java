package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.ParsedProject;
import dev.logast.parse.ProjectParser;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Compiled classes on the classpath must not change results; incremental re-parses resolve other files via bytecode. */
class IncrementalParseTest {
    @TempDir
    Path tmp;

    private static List<Path> withClasses() {
        List<Path> cp = new ArrayList<>(Fixtures.classpath());
        cp.add(Fixtures.root().resolve("build/classes/java/main"));
        return cp;
    }

    @Test
    void sameOrdersWithCompiledClassesAndIncrementalReparse() throws Exception {
        ScanResult reference = Rules.scan(Fixtures.analysis());
        ParsedProject parsed = new ProjectParser().parse(Fixtures.root(), withClasses());
        ScanResult withBytecode = Rules.scan(Analysis.of(parsed, Fixtures.policy()));
        assertThat(ids(withBytecode)).isEqualTo(ids(reference));
        assertThat(withBytecode.stats().unresolvedCalls()).isZero();

        String rel = "src/main/java/com/example/shop/payment/CardGateway.java";
        ParsedProject again = new ProjectParser().reparse(parsed, Set.of(rel), Set.of());
        assertThat(again.files()).hasSameSizeAs(parsed.files());
        assertThat(ids(Rules.scan(Analysis.of(again, Fixtures.policy())))).isEqualTo(ids(reference));
    }

    private static List<String> ids(ScanResult r) {
        return r.orders().stream().map(WorkOrder::id).sorted().toList();
    }
}
