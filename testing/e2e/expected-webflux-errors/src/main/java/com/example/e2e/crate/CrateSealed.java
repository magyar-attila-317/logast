package com.example.e2e.crate;

public class CrateSealed extends RuntimeException {
    public CrateSealed(String message) {
        super(message);
    }
}
