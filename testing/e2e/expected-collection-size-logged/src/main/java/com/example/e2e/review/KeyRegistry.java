package com.example.e2e.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class KeyRegistry {
    private static final Logger log = LoggerFactory.getLogger(KeyRegistry.class);
    private final List<Item> items = new ArrayList<>();

    record Key(String name) {
    }

    record Item(String name) {
    }

    List<Item> getItems() {
        return items;
    }

    void register(Set<Key> existingKeys) {
        log.warn("{} keys have an entity already associated with them: {}", existingKeys.size(), existingKeys);
    }

    void flush() {
        log.info("Flushing {} pending items: {}", getItems().size(), getItems());
    }
}
