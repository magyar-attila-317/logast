package com.example.e2e.box;

public class Box extends AuditableRecord {
    private final String id;

    public Box(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
