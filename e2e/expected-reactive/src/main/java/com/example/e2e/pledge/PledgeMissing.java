package com.example.e2e.pledge;

public class PledgeMissing extends RuntimeException {
    public PledgeMissing(String pledgeId, Throwable cause) {
        super("pledge not found: " + pledgeId, cause);
    }
}
