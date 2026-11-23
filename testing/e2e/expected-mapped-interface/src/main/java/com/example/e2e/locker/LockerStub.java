package com.example.e2e.locker;

import org.springframework.stereotype.Component;

/** Implements the mapped interface without being a controller (a client stub): no endpoint. */
@Component
public class LockerStub implements LockerApi {
    @Override
    public String status(String lockerId) {
        return "unknown";
    }

    @Override
    public String open(String lockerId) {
        return "unknown";
    }
}
