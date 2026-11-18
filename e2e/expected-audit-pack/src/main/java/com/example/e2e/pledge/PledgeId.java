package com.example.e2e.pledge;

/** A typed id: in MDC it must be its value, not PledgeId[value=...] (#49). */
public record PledgeId(String value) {
}
