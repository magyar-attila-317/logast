package com.example.shop.web;

import com.example.shop.domain.OrderLine;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateOrderRequest(@NotBlank String customerId, String email, List<OrderLine> lines) {
}
