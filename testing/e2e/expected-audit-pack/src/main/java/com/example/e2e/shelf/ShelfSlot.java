package com.example.e2e.shelf;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedBy;

/** Audited: Spring Data fills lastModifiedBy when a slot is saved (table shelf_slot). */
public class ShelfSlot {
    @Id
    private String id;
    private String status;
    @LastModifiedBy
    private String lastModifiedBy;
}
