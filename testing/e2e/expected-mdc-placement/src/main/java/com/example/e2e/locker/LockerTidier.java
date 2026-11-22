package com.example.e2e.locker;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LockerTidier {
    private static final Logger log = LoggerFactory.getLogger(LockerTidier.class);

    public void tidy(String lockerCode) {
        try (var mdc = MdcScope.of("lockerCode", lockerCode.trim())) {
            log.info("Locker tidied");
        }
    }
}
