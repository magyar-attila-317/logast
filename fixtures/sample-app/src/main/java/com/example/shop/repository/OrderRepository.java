package com.example.shop.repository;

import com.example.shop.domain.Order;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class OrderRepository {
    private final Map<String, Order> orders = new ConcurrentHashMap<>();

    public void save(Order order) {
        orders.put(order.id(), order);
    }

    public Optional<Order> find(String orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    public void delete(String orderId) {
        orders.remove(orderId);
    }

    public Collection<Order> findAll() {
        return List.copyOf(orders.values());
    }
}
