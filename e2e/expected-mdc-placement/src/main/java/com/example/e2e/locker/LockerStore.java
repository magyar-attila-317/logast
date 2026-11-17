package com.example.e2e.locker;

import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class LockerStore {
    private final Set<String> open = new HashSet<>();

    public void open(String lockerCode) {
        open.add(lockerCode);
    }

    public void close(String lockerCode) {
        open.remove(lockerCode);
    }
}
