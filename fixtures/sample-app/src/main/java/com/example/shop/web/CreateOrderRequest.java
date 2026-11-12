package com.example.shop.web;

import com.example.shop.domain.OrderLine;

import java.util.List;

public record CreateOrderRequest(String customerId, String email, List<OrderLine> lines) {
}
