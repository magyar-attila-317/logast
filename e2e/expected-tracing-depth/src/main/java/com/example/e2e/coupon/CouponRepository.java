package com.example.e2e.coupon;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate repository: an update, an upsert through queryForObject, a list read. */
@Repository
public class CouponRepository {
    private static final String CLOSE = "UPDATE coupons SET closed = true WHERE coupon_id = ?";
    private static final String COUNT_USE = """
            INSERT INTO coupon_usage (coupon_id, uses) VALUES (?, 1)
            ON CONFLICT (coupon_id) DO UPDATE SET uses = coupon_usage.uses + 1
            RETURNING uses
            """;
    private final JdbcTemplate jdbcTemplate;

    public CouponRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void close(String couponId) {
        jdbcTemplate.update(CLOSE, couponId);
    }

    public int countUse(String couponId) {
        return jdbcTemplate.queryForObject(COUNT_USE, Integer.class, couponId);
    }

    public List<String> findOpen() {
        return jdbcTemplate.queryForList("SELECT coupon_id FROM coupons WHERE closed = false", String.class);
    }
}
