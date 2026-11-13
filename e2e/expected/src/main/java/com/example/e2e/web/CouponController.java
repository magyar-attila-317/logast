package com.example.e2e.web;

import com.example.e2e.coupon.CouponRepository;
import com.example.e2e.coupon.CouponService;
import com.example.e2e.logging.MdcScope;
import com.example.e2e.order.Order;
import com.example.e2e.order.OrderService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class CouponController {
    private final CouponService coupons;
    private final CouponRepository repository;
    private final OrderService orders;

    public CouponController(CouponService coupons, CouponRepository repository, OrderService orders) {
        this.coupons = coupons;
        this.repository = repository;
        this.orders = orders;
    }

    @GetMapping("/coupons")
    public List<String> open() {
        return repository.findOpen();
    }

    @PostMapping("/coupons/{couponId}/publish")
    public void publish(@PathVariable String couponId) {
        try (var mdc = MdcScope.of("couponId", couponId)) {
            coupons.publish(couponId);
        }
    }

    @PostMapping("/coupons/{couponId}/use")
    public int use(@PathVariable String couponId) {
        try (var mdc = MdcScope.of("couponId", couponId)) {
            return repository.countUse(couponId);
        }
    }

    @PostMapping("/orders")
    public void place(@RequestBody Order order) {
        try (var mdc = MdcScope.of("orderId", order.id())) {
            orders.place(order);
        }
    }
}
