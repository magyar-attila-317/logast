package com.example.e2e.web;

import com.example.e2e.coupon.CouponRepository;
import com.example.e2e.coupon.CouponService;
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
        coupons.publish(couponId);
    }

    @PostMapping("/coupons/{couponId}/use")
    public int use(@PathVariable String couponId) {
        return repository.countUse(couponId);
    }

    @PostMapping("/coupons/redeem")
    public void redeem(@RequestBody com.example.e2e.coupon.RedeemRequest request) {
        coupons.redeem(request);
    }

    @PostMapping("/orders")
    public void place(@RequestBody Order order) {
        orders.place(order);
    }
}
