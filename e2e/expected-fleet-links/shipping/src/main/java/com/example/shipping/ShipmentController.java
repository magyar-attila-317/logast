package com.example.shipping;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shipments")
class ShipmentController {
    private static final Logger log = LoggerFactory.getLogger(ShipmentController.class);
    private final Counter completed;

    ShipmentController(MeterRegistry registry) {
        this.completed = Counter.builder("shipment.completed").register(registry);
    }

    @GetMapping("/{id}")
    String status(@PathVariable long id) {
        log.info("Shipment status read [shipmentId={}]", id);
        return "sent";
    }

    void delivered(String orderId) {
        log.info("Order shipped [orderId={}]", orderId);
        completed.increment();
    }
}
