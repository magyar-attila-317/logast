package com.example.shop.service;

import com.example.shop.domain.Order;
import com.example.shop.web.CreateOrderRequest;

public interface OrderService {
    Order place(CreateOrderRequest request);

    void cancel(String orderId);
}
