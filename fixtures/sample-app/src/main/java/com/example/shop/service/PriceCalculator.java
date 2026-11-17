package com.example.shop.service;

import com.example.shop.domain.OrderLine;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PriceCalculator {
    public long total(List<OrderLine> lines) {
        return sum(lines, 0);
    }

    private long sum(List<OrderLine> lines, int index) {
        if (index >= lines.size()) {
            return 0;
        }
        OrderLine line = lines.get(index);
        return line.qty() * line.priceCents() + sum(lines, index + 1);
    }

    /** #58 f: near miss, a specific exception mapped to a default is a decision, not a hidden failure. */
    public int quantity(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) { // expect: SWALLOWED_EXCEPTION
            return 0; // expect-not: CATCH_ALL_DEFAULT
        }
    }
}
