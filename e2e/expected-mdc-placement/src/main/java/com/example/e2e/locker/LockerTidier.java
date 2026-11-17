package com.example.e2e.locker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LockerTidier {
    private static final Logger log = LoggerFactory.getLogger(LockerTidier.class);

    public void tidy(String lockerCode) {
        log.info("Locker tidied");
    }
}
