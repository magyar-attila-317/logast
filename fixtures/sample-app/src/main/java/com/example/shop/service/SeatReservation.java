package com.example.shop.service;

import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * #90: a retried method called inside another transaction joins it (its retries run in a transaction already marked
 * rollback-only); one with a transaction of its own per attempt is the near miss.
 */
@Service
public class SeatReservation {
    private final SeatStore seats;

    public SeatReservation(SeatStore seats) {
        this.seats = seats;
    }

    @Transactional
    public int reserveAll(String orderId) {
        return seats.hold(orderId) + seats.confirm(orderId);
    }

    @Service
    public static class SeatStore {
        @Retryable(maxRetries = 2)
        @Transactional
        public int hold(String orderId) { // expect: RETRY_IN_TRANSACTION, RETRY_INACTIVE, RETRY_SETTINGS_HARDCODED
            return 1;
        }

        @Retryable(maxRetries = 2)
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public int confirm(String orderId) { // expect: RETRY_INACTIVE, RETRY_SETTINGS_HARDCODED // expect-not: RETRY_IN_TRANSACTION
            return 2;
        }
    }
}
