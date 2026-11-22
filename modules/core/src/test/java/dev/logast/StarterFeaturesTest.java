package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #93: the starter's behaviours are off unless their property is true. A service that already has the starter (it got
 * them by default before) gets the properties of the selected packs written, so an apply keeps what it had; a value the
 * service set is left alone.
 */
class StarterFeaturesTest {
    @TempDir
    Path tmp;

    private Path service() throws IOException {
        Path root = tmp.resolve("app");
        try (Stream<Path> s = Files.walk(Fixtures.root())) {
            for (Path p : s.filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/.logast/")).toList()) {
                Path to = root.resolve(Fixtures.root().relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(p, to);
                }
            }
        }
        return root;
    }

    /** The service with the starter jar on its classpath (as after its build resolved the dependency). */
    private ScanResult scanWithStarter(Path root) throws IOException {
        Path jar = tmp.resolve("logging-support.jar");
        if (!Files.exists(jar)) {
            Path classes = Path.of(System.getProperty("logast.support.classes"));
            try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar)); Stream<Path> s = Files.walk(classes)) {
                for (Path c : s.filter(Files::isRegularFile).toList()) {
                    out.putNextEntry(new java.util.zip.ZipEntry(classes.relativize(c).toString().replace('\\', '/')));
                    out.write(Files.readAllBytes(c));
                    out.closeEntry();
                }
            }
        }
        List<Path> cp = new ArrayList<>(Fixtures.classpath());
        cp.add(jar);
        return Logast.scan(root, cp, Logast.policy(root, null));
    }

    private static WorkOrder starterOrder(ScanResult r) {
        List<WorkOrder> setup = r.orders().stream().filter(o -> o.rule().equals(Rules.SETUP_SUPPORT_STARTER)).toList();
        assertThat(setup).hasSize(1);
        return setup.getFirst();
    }

    @Test
    void aServiceWithTheStarterKeepsItsBehavioursOnUpgrade() throws IOException {
        WorkOrder o = starterOrder(scanWithStarter(service()));
        assertThat(o.title()).startsWith("Switch on the logging-support behaviours");
        assertThat(o.edits()).extracting(e -> e.kind() + " " + e.file()).containsExactly("yaml-merge src/main/resources/application.yml");
        assertThat(o.edits().getFirst().text()).isEqualTo("logging-support:\n  log-defaults:\n    enabled: true\n"
                + "  mdc-hygiene:\n    enabled: true\n  context-propagation:\n    enabled: true\n  batch-trace:\n    enabled: true\n");
        // without its property the starter's TaskDecorator is off: @Async loses MDC until this order applies
        assertThat(o.locations()).extracting(WorkOrder.Location::rule).contains(Rules.ASYNC_CONTEXT_LOSS);
    }

    @Test
    void aValueTheServiceSetStays() throws IOException {
        Path root = service();
        Path yml = root.resolve("src/main/resources/application.yml");
        Files.writeString(yml, Files.readString(yml) + "logging-support:\n  mdc-hygiene:\n    enabled: false\n"
                + "  context-propagation:\n    enabled: true\n");
        WorkOrder o = starterOrder(scanWithStarter(root));
        assertThat(o.edits().getFirst().text()).isEqualTo("logging-support:\n  log-defaults:\n    enabled: true\n"
                + "  batch-trace:\n    enabled: true\n");
        assertThat(o.locations()).as("the decorator is on").extracting(WorkOrder.Location::rule).doesNotContain(Rules.ASYNC_CONTEXT_LOSS);
        Files.writeString(yml, Files.readString(yml) + "  log-defaults:\n    enabled: true\n  batch-trace:\n    enabled: false\n");
        assertThat(scanWithStarter(root).orders()).as("all set: nothing to do")
                .noneMatch(w -> w.rule().equals(Rules.SETUP_SUPPORT_STARTER));
    }
}
