package dev.logast.apply;

import dev.logast.orders.Edit;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applier guards against rules that misbehave: an order that comes back unchanged after its own edit (the rule does
 * not see its fix) is applied once and reported, not repeated on every pass; fixes that undo each other stop the run.
 */
class ApplierGuardTest {
    @TempDir
    Path root;

    private static WorkOrder order(String id, Edit edit) {
        return new WorkOrder(id, Rules.DB_OUTCOME_UNLOGGED, List.of(Rules.DB_OUTCOME_UNLOGGED), "low", "test " + id,
                "A.java", 1, List.of(), null, List.of(), "", List.of(edit), Map.of(), WorkOrder.Context.EMPTY, List.of(), Map.of());
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Applier.Result apply(Function<Path, ScanResult> scanner) {
        return new Applier(scanner, new FillValidator(Policy.defaults()))
                .apply(root, new Applier.Selection(true, Set.of(), Map.of()), true);
    }

    @Test
    void aRuleThatDoesNotSeeItsOwnFixIsAppliedOnceAndReported() throws IOException {
        Files.writeString(root.resolve("A.java"), "class A {\n}\n");
        // the rule always wants the line, whatever is in the file (as DB_OUTCOME_UNLOGGED did with an unresolved logger)
        Function<Path, ScanResult> blind = work -> {
            String text = read(work.resolve("A.java"));
            return new ScanResult(1, work.toString(), null, Map.of(),
                    List.of(order("DBO-blind", Edit.insert("A.java", text.lastIndexOf('}'), "    // added\n"))));
        };

        Applier.Result r = apply(blind);

        assertThat(read(root.resolve("A.java"))).isEqualTo("class A {\n    // added\n}\n");
        assertThat(r.violations()).anyMatch(v -> v.contains("DBO-blind") && v.contains("did not see its own fix"));
    }

    @Test
    void fixesThatUndoEachOtherStopTheRun() throws IOException {
        String plain = "class A {\n}\n";
        String marked = "class A {\n    // marker\n}\n";
        Files.writeString(root.resolve("A.java"), plain);
        // one order adds the marker, another removes it again
        Function<Path, ScanResult> flapping = work -> {
            String text = read(work.resolve("A.java"));
            WorkOrder o = text.contains("marker")
                    ? order("DBO-remove", Edit.replace("A.java", 0, text.length(), plain))
                    : order("DBO-add", Edit.replace("A.java", 0, text.length(), marked));
            return new ScanResult(1, work.toString(), null, Map.of(), List.of(o));
        };

        Applier.Result r = apply(flapping);

        assertThat(r.violations()).anyMatch(v -> v.contains("undo each other") && v.contains("DBO-add") && v.contains("DBO-remove"));
        assertThat(r.applied()).containsExactlyInAnyOrder("DBO-add", "DBO-remove");
    }
}
