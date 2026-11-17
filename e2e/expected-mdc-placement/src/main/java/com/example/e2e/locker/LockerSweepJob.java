package com.example.e2e.locker;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LockerSweepJob {
    private static final Logger log = LoggerFactory.getLogger(LockerSweepJob.class);

    @Scheduled(fixedDelay = 60000)
    public void sweep() {
        log.info("Locker sweep started"); // logast:wording
        process(nextLocker());
        log.info("Locker sweep completed"); // logast:wording
    }

    void process(String lockerCode) {
        try (var mdc = MdcScope.of("lockerCode", lockerCode)) {
            rotate(lockerCode);
            relabel(lockerCode);
            inspect(lockerCode);
            recount(lockerCode);
            stamp(lockerCode);
        }
    }

    void recount(String lockerCode) {
        log.info("Locker recounted");
    }

    void stamp(String lockerCode) {
        try (var mdc = MdcScope.of("lockerCode", lockerCode.toLowerCase())) {
            log.info("Locker stamped");
        }
    }

    void rotate(String lockerCode) {
        String slot = lockerCode.toUpperCase();
        try (var mdc = MdcScope.of("lockerCode", slot)) {
            log.info("Locker slot rotated");
        }
    }

    void relabel(String lockerCode) {
        try (var mdc = MdcScope.of("lockerCode", lockerCode.strip())) {
            log.info("Locker relabelled");
        }
    }

    void inspect(String lockerCode) {
        try (var mdc = MdcScope.of("lockerCode", "L-" + lockerCode)) {
            log.info("Locker inspected");
        }
    }

    private String nextLocker() {
        return "L1";
    }
}
