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
}
