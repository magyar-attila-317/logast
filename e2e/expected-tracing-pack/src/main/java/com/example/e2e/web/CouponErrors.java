package com.example.e2e.web;

import com.example.e2e.coupon.CouponMissing;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps a missing coupon to 404 without logging it. */
@RestControllerAdvice
public class CouponErrors {
    @ExceptionHandler(CouponMissing.class)
    public ResponseEntity<Void> missing(CouponMissing e) {
        return ResponseEntity.notFound().build();
    }
}
