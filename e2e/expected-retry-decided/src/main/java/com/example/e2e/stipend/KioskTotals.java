package com.example.e2e.stipend;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One pledge's kiosk for a day: the pledge id travels inside this record, never as a parameter of its own. */
public record KioskTotals(String pledgeId, LocalDate kioskDay, BigDecimal totalDiscount) {
}
