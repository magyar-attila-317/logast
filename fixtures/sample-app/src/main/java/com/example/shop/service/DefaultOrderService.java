package com.example.shop.service;

import com.example.shop.domain.Order;
import com.example.shop.domain.OrderLine;
import com.example.shop.domain.PaymentException;
import com.example.shop.notification.NotificationService;
import com.example.shop.payment.PaymentClient;
import com.example.shop.repository.OrderRepository;
import com.example.shop.web.CreateOrderRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class DefaultOrderService implements OrderService {
    private static final Logger log = LoggerFactory.getLogger(DefaultOrderService.class);

    private final OrderRepository repository;
    private final PaymentClient paymentClient;
    private final PriceCalculator priceCalculator;
    private final NotificationService notificationService;

    public DefaultOrderService(OrderRepository repository, PaymentClient paymentClient,
                               PriceCalculator priceCalculator, NotificationService notificationService) {
        this.repository = repository;
        this.paymentClient = paymentClient;
        this.priceCalculator = priceCalculator;
        this.notificationService = notificationService;
    }

    @Override
    public Order place(CreateOrderRequest request) { // expect: REPEATED_LOG_VALUE
        Order order = new Order(UUID.randomUUID().toString(), request.customerId(), request.email(), request.lines());
        request.lines().forEach(this::validateLine);
        long amount = priceCalculator.total(order.lines());
        repository.save(order);
        try {
            paymentClient.charge(order, amount);
        } catch (PaymentException e) { // expect: SWALLOWED_EXCEPTION
            // payment is retried by the reconciliation job
        }
        log.info("Placed purchase " + order.id()); // expect: STRING_CONCAT_LOG, GLOSSARY_VIOLATION, LOG_FORMAT
        notificationService.sendConfirmation(order);
        return order;
    }

    @Override
    public void cancel(String orderId) {
        repository.find(orderId).ifPresent(order -> {
            paymentClient.refund(orderId);
            repository.delete(orderId);
            log.info("Cancelled order {}", orderId); // expect: LOG_FORMAT
        });
    }

    private void validateLine(OrderLine line) {
        if (line.qty() <= 0) {
            throw new IllegalArgumentException("Quantity must be positive for sku " + line.sku());
        }
    }
}
