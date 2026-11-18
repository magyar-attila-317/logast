package com.example.e2e.berth;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class BerthController {
    private final BerthService service;

    BerthController(BerthService service) {
        this.service = service;
    }

    @PostMapping("/berths/{berthCode}/release")
    void release(@PathVariable String berthCode) {
        service.release(berthCode);
    }

    @PostMapping("/berths/{berthCode}/assign")
    void assign(@PathVariable String berthCode) {
        service.assign(berthCode);
    }
}
