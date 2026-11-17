package com.example.e2e.locker;

import com.example.e2e.logging.MdcScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
public class LockerController {
    private final LockerStore store;
    private final LockerTidier tidier;

    public LockerController(LockerStore store, LockerTidier tidier) {
        this.store = store;
        this.tidier = tidier;
    }

    @PostMapping("/lockers/{lockerCode}/tidy")
    public void tidy(@PathVariable String lockerCode) {
        try (var mdc = MdcScope.of("lockerCode", lockerCode)) {
            tidier.tidy(lockerCode);
        }
    }

    @PostMapping("/lockers/{lockerCode}/open")
    public void open(@PathVariable String lockerCode) {
        try (var mdc = MdcScope.of("lockerCode", lockerCode)) {
            log.info("Locker change started");
            store.open(lockerCode);
        }
    }

    @PostMapping("/lockers/{lockerCode}/close")
    public void close(@PathVariable String lockerCode) {
        try (var mdc = MdcScope.of("lockerCode", lockerCode)) {
            log.info("Locker change started");
            store.close(lockerCode);
        }
    }

    @PostMapping("/lockers/{lockerCode}/reopen")
    public void reopen(@PathVariable String lockerCode) {
        try (var mdc = MdcScope.of("lockerCode", lockerCode)) {
            log.info("Locker change started");
            store.open(lockerCode);
        }
    }

    @PostMapping("/lockers/{lockerCode}/swap")
    public void swap(@PathVariable String lockerCode, String otherCode) {
        log.info("Locker change started");
        store.close(lockerCode);
        try (var mdc = MdcScope.of("lockerCode", otherCode)) {
            store.open(otherCode);
        }
    }
}
