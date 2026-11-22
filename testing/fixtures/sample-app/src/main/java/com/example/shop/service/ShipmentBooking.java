package com.example.shop.service;

import com.example.shop.clients.ShippingClient;
import com.example.shop.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

/**
 * #58 f: near miss, the remote call runs after the transaction, from a caller that has none. A checked exception that
 * commits, and one with a rollback rule.
 */
@Service
public class ShipmentBooking {
    private final BookingStore store;
    private final ShippingClient shipping;

    public ShipmentBooking(BookingStore store, ShippingClient shipping) {
        this.store = store;
        this.shipping = shipping;
    }

    public String book(String orderId) {
        store.markBooked(orderId);
        return shipping.status(orderId);
    }

    @Service
    public static class BookingStore {
        private final OrderRepository repository;

        public BookingStore(OrderRepository repository) {
            this.repository = repository;
        }

        @Transactional
        public void markBooked(String orderId) { // expect-not: REMOTE_CALL_IN_TRANSACTION
            repository.delete(orderId);
        }

        @Transactional
        public void release(String orderId) throws IOException { // expect: TX_COMMITS_ON_CHECKED
            repository.delete(orderId);
        }

        @Transactional(rollbackFor = IOException.class)
        public void withdraw(String orderId) throws IOException { // expect-not: TX_COMMITS_ON_CHECKED
            repository.delete(orderId);
        }
    }
}
