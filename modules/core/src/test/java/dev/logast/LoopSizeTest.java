package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** levels.infoInLoops: size: INFO per item depends on where the items come from, not on a single switch. */
class LoopSizeTest {
    @Test
    void infoPerItemDependsOnTheLoopSize(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Item.java"), "package x;\npublic record Item(String id) {}\n");
        Files.writeString(src.resolve("ItemRepository.java"), """
                package x;
                import java.util.List;
                public interface ItemRepository extends org.springframework.data.repository.CrudRepository<Item, String> {
                    List<Item> findByActiveTrue();
                }
                """);
        Files.writeString(src.resolve("Items.java"), """
                package x;

                import java.util.List;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Items {
                    private static final Logger log = LoggerFactory.getLogger(Items.class);
                    private final ItemRepository repository;

                    Items(ItemRepository repository) {
                        this.repository = repository;
                    }

                    void fromQuery() {
                        for (Item i : repository.findByActiveTrue()) {
                            log.info("Item checked [itemId={}]", i.id());
                        }
                    }

                    void fromQueryVariable() {
                        List<Item> items = repository.findByActiveTrue();
                        items.forEach(i -> log.info("Item checked [itemId={}]", i.id()));
                    }

                    void unknown(List<Item> items) {
                        for (Item i : items) {
                            log.info("Item checked [itemId={}]", i.id());
                        }
                    }

                    void hinted(List<Item> items) {
                        // logast:loop-size 10
                        for (Item i : items) {
                            log.info("Item checked [itemId={}]", i.id());
                        }
                    }

                    void hintedLarge(List<Item> items) {
                        // logast:loop-size large
                        for (Item i : items) {
                            log.info("Item checked [itemId={}]", i.id());
                        }
                    }

                    void fixed() {
                        for (String s : List.of("a", "b")) {
                            log.info("Channel checked [channel={}]", s);
                        }
                    }
                }
                """);
        Files.writeString(dir.resolve("logast.yaml"), "levels: { infoInLoops: size }\n");
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        Map<String, String> byMethod = r.orders().stream()
                .filter(o -> o.fixes().contains(Rules.LOG_LEVEL) || o.fixes().contains(Rules.LOG_IN_LOOP))
                .collect(Collectors.toMap(WorkOrder::method, o -> o.fixes().contains(Rules.LOG_LEVEL) ? "debug" : "report", (a, b) -> a));
        assertThat(byMethod).containsEntry("Items.fromQuery", "debug")            // database rows: lowered to DEBUG
                .containsEntry("Items.fromQueryVariable", "debug")                  // ... also through a local variable
                .containsEntry("Items.unknown", "report")                           // unknown size: a person decides
                .containsEntry("Items.hintedLarge", "debug")
                .doesNotContainKey("Items.hinted")                                  // declared small: INFO stays
                .doesNotContainKey("Items.fixed");                                  // two fixed items: INFO stays
    }

    /**
     * #286: a line reporting a state change or a mismatch keeps INFO even once per item of query data: a device going
     * OFFLINE or a price discrepancy is what support looks for. Reported for a person to decide, not edited.
     */
    @Test
    void stateChangesAndMismatchesInALoopKeepTheirLevel(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Device.java"), "package x;\npublic record Device(String id, String status, long quoted, long received) {}\n");
        Files.writeString(src.resolve("DeviceRepository.java"), """
                package x;
                import java.util.List;
                public interface DeviceRepository extends org.springframework.data.repository.CrudRepository<Device, String> {
                    List<Device> findByActiveTrue();
                }
                """);
        Files.writeString(src.resolve("Devices.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public class Devices {
                    private static final Logger log = LoggerFactory.getLogger(Devices.class);
                    private final DeviceRepository repository;

                    Devices(DeviceRepository repository) {
                        this.repository = repository;
                    }

                    void poll() {
                        for (Device d : repository.findByActiveTrue()) {
                            log.info("Device {} status changed: {} -> OFFLINE", d.id(), d.status());
                        }
                    }

                    void compare() {
                        for (Device d : repository.findByActiveTrue()) {
                            log.info("Price discrepancy: quoted {} received {}", d.quoted(), d.received());
                        }
                    }

                    void check() {
                        for (Device d : repository.findByActiveTrue()) {
                            log.info("Device checked [deviceId={}]", d.id());
                        }
                    }
                }
                """);
        Files.writeString(dir.resolve("logast.yaml"), "levels: { infoInLoops: size }\n");
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        Map<String, WorkOrder> byMethod = r.orders().stream().filter(o -> o.fixes().contains(Rules.LOG_LEVEL))
                .collect(Collectors.toMap(WorkOrder::method, o -> o, (a, b) -> a));
        assertThat(byMethod.get("Devices.poll").edits()).as("a state change: reported, not lowered").isEmpty();
        assertThat(byMethod.get("Devices.compare").edits()).as("a mismatch: reported, not lowered").isEmpty();
        assertThat(byMethod.get("Devices.check").edits()).as("a plain per-item line still goes to DEBUG").isNotEmpty();
    }
}
