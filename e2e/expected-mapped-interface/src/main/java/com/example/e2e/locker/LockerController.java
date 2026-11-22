package com.example.e2e.locker;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LockerController implements LockerApi {
    private static final Logger log = LoggerFactory.getLogger(LockerController.class);

    @Override
    public String status(String lockerId) {
        try (var mdc = MdcScope.of("lockerId", lockerId)) {
            log.debug("Locker status requested");
            return "closed";
        }
    }

    @Override
    public String open(String lockerId) {
        try (var mdc = MdcScope.of("lockerId", lockerId)) {
            log.info("Locker open started"); // logast:wording
            log.debug("Locker opened");
            return "open";
        }
    }

    @GetMapping("/" + "free")
    public int free() {
        return 3;
    }
}
