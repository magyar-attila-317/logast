package com.example.shop.web;

import com.example.shop.domain.Order;
import com.example.shop.domain.OrderLine;
import com.example.shop.domain.OrderNotFoundException;
import com.example.shop.repository.OrderRepository;
import com.example.shop.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;
    private final OrderRepository repository;

    public OrderController(OrderService orderService, OrderRepository repository) {
        this.orderService = orderService;
        this.repository = repository;
    }

    @PostMapping
    public Order create(@RequestBody CreateOrderRequest request) { // expect: MISSING_DOMAIN_MDC
        log.info("Creating order for customer {}", request.customerId()); // expect: LOG_FORMAT
        return orderService.place(request);
    }

    @GetMapping("/{orderId}")
    public Order get(@PathVariable String orderId) { // expect: MISSING_DOMAIN_MDC
        log.info("Fetching order {}", orderId); // expect: LOG_FORMAT
        return repository.find(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @GetMapping("/{orderId}/lines")
    public List<OrderLine> lines(@PathVariable String orderId) { // expect: MISSING_DOMAIN_MDC
        return repository.find(orderId).map(Order::lines).orElse(List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<String> invalid(MethodArgumentNotValidException e) {
        log.error("Order request rejected", e); // expect: LOG_LEVEL
        return ResponseEntity.badRequest().body("invalid");
    }

    @DeleteMapping("/{orderId}")
    public void cancel(@PathVariable String orderId) {
        try (var ignored = MDC.putCloseable("order.id", orderId)) {
            log.info("Cancelling order {}", orderId); // expect: LOG_FORMAT
            orderService.cancel(orderId);
        }
    }
}
