package com.example.e2e.relay;

/** A message payload: its pledgeId accessor is the MDC value. */
public record PledgeReturn(String pledgeId, int quantity) {
}
