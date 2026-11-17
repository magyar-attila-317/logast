package com.example.e2e.locker;

public final class LockerPaths {
    public static final String BASE = "/lockers";
    public static final String LOCKER = "/{lockerId}";
    public static final String OPEN = LOCKER + "/open";

    private LockerPaths() {
    }
}
