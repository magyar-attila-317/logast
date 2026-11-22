package com.example.e2e.locker;

import com.example.e2e.logging.ObservedContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Implements the mapped interface without being a controller (a client stub): no endpoint. */
@Slf4j
@Component
public class LockerStub implements LockerApi {
    @Override
    public String status(String lockerId) {
        return "unknown";
    }

    @Override
    public String open(String lockerId) {
        return ObservedContext.entrypoint(LockerMetrics.LOCKER_STUB)
                .quiet()
                .call(() -> {
                    log.info("Locker stub started"); // logast:wording
                    return "unknown";
                });
    }
}
