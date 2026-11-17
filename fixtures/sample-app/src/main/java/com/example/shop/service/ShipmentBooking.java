package com.example.shop.service;

import com.example.shop.clients.ShippingClient;
import com.example.shop.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** #58 f: near miss, the remote call runs after the transaction, from a caller that has none. */
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
    }
}
