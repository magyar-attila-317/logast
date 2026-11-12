package com.example.shop.domain;

import java.util.List;

public record Order(String id, String customerId, String email, List<OrderLine> lines) {
}
