package com.example.e2e.web;

import com.example.e2e.pledge.PledgeRepository;
import com.example.e2e.pledge.PledgeService;
import com.example.e2e.logging.MdcScope;
import com.example.e2e.order.Order;
import com.example.e2e.order.OrderService;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
public class PledgeController {
    private final PledgeService pledges;
    private final PledgeRepository repository;
    private final OrderService orders;

    public PledgeController(PledgeService pledges, PledgeRepository repository, OrderService orders) {
        this.pledges = pledges;
        this.repository = repository;
        this.orders = orders;
    }

    @GetMapping("/pledges")
    public List<String> open() {
        return repository.findOpen();
    }

    @PostMapping("/pledges/{pledgeId}/publish")
    public void publish(@PathVariable String pledgeId) {
        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
            pledges.publish(pledgeId);
        }
    }

    @PostMapping("/pledges/{pledgeId}/use")
    public int use(@PathVariable String pledgeId) {
        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
            return repository.countUse(pledgeId);
        }
    }

    @PostMapping("/pledges/allot")
    public void allot(@RequestBody com.example.e2e.pledge.AllotRequest request) {
        pledges.allot(request);
    }

    @DeleteMapping("/pledges/{pledgeId}")
    public void delete(@PathVariable String pledgeId) {
        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
            log.info("Pledge sweep started");
            pledges.delete(pledgeId);
        }
    }

    @PostMapping("/admin/pledges/{pledgeId}/revoke")
    public void revoke(@PathVariable String pledgeId, @RequestHeader("X-User") String userId) {
        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
            log.info("Pledge sweep started");
            pledges.revoke(pledgeId, userId);
        }
    }

    @PostMapping("/orders")
    public void place(@RequestBody Order order) {
        try (var mdc = MdcScope.of("orderId", order.id())) {
            orders.place(order);
        }
    }
}
