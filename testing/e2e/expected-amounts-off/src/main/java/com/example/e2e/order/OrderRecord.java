package com.example.e2e.order;

import org.springframework.data.annotation.Id;

/** Stored through a CrudRepository; nothing says who last changed it. */
public class OrderRecord {
    @Id
    private String id;
    private long amountCents;

    public String getId() {
        return id;
    }

    public long getAmountCents() {
        return amountCents;
    }
}
