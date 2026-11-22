package com.example.orders;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "shipmentStatusClient")
interface ShipmentStatusApi {
    @GetMapping("/shipments/{id}")
    String status(@PathVariable String id);
}
