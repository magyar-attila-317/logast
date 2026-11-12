package com.example.shop.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReportController {
    private final MeterRegistry registry;

    public ReportController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/reports/daily")
    public String daily() {
        Timer.Sample sample = Timer.start(registry); // expect: METRIC_DIRECT_REGISTRY
        try {
            return "report";
        } finally {
            sample.stop(registry.timer("report.daily")); // expect: METRIC_DIRECT_REGISTRY
        }
    }
}
