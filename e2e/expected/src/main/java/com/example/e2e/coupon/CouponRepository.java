package com.example.e2e.coupon;

import com.example.e2e.logging.AfterCommit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate repository: an update, an upsert through queryForObject, a list read. */
@Repository
public class CouponRepository {
    private static final Logger log = LoggerFactory.getLogger(CouponRepository.class);

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
        int rows = jdbcTemplate.update(CLOSE, couponId);
        AfterCommit.log(() -> log.debug("Coupons updated [rows={}]", rows)); // logast:wording
    }

    public int countUse(String couponId) {
        int uses = jdbcTemplate.queryForObject(COUNT_USE, Integer.class, couponId);
        AfterCommit.log(() -> log.info("Coupon usage saved [uses={}]", uses)); // logast:wording
        return uses;
    }

    public List<String> findOpen() {
        List<String> result = jdbcTemplate.queryForList("SELECT coupon_id FROM coupons WHERE closed = false", String.class);
        log.debug("Coupons read [count={}]", result.size()); // logast:wording
        return result;
    }
}
