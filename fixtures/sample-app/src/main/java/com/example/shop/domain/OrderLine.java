package com.example.shop.domain;

public record OrderLine(String sku, int qty, long priceCents) {
}
