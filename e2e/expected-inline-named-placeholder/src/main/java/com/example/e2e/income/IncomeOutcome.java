package com.example.e2e.income;

import java.math.BigDecimal;

public record IncomeOutcome(boolean excluded, BigDecimal income) {
}
