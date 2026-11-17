package com.example.e2e.stipend;

import java.math.BigDecimal;

public record PledgeDetails(String pledgeId, String sponsorshipId, String origin, BigDecimal subsidyPercentage) {
}
