package dev.logast.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** #283: scratch folders a killed apply left behind are swept by the next run; a live run's folder stays. */
class ApplierScratchTest {
    @TempDir
    Path tmp;

    private Path scratch(String name, Long pid) throws Exception {
        Path d = Files.createDirectories(tmp.resolve(name).resolve("project/src"));
        Files.writeString(d.resolve("A.java"), "class A {}");
        if (pid != null) {
            Files.writeString(tmp.resolve(name).resolve(Applier.PID_FILE), String.valueOf(pid));
        }
        return tmp.resolve(name);
    }

    @Test
    void foldersOfDeadRunsGoLiveOnesStay() throws Exception {
        Process gone = new ProcessBuilder("java", "-version").redirectErrorStream(true).start();
        gone.getInputStream().readAllBytes();
        gone.waitFor();
        long live = ProcessHandle.current().parent().orElseThrow().pid();

        Path dead = scratch("logast-apply-1", gone.pid());
        Path running = scratch("logast-apply-2", live);
        Path mine = scratch("logast-apply-3", ProcessHandle.current().pid());
        Path oldWithoutPid = scratch("logast-apply-4", null);
        Files.setLastModifiedTime(oldWithoutPid, FileTime.from(Instant.now().minus(Duration.ofHours(7))));
        Path freshWithoutPid = scratch("logast-apply-5", null);
        Path other = scratch("something-else", gone.pid());

        assertThat(Applier.sweepStale(tmp, Duration.ofHours(6))).isEqualTo(2);
        assertThat(dead).as("its process is gone").doesNotExist();
        assertThat(oldWithoutPid).as("an earlier logast's leftover, older than 6 h").doesNotExist();
        assertThat(running).as("another live run").exists();
        assertThat(mine).as("this run").exists();
        assertThat(freshWithoutPid).as("maybe an earlier logast still running").exists();
        assertThat(other).as("not logast's").exists();
    }
}
