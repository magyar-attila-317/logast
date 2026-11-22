package com.example.e2e.pledge;

/** The pledge id arrives inside a request object, not as a parameter named pledgeId. */
public record AllotRequest(String code, String tableId) {
}
