package com.example.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class EntryBook {
    private static final Logger log = LoggerFactory.getLogger(EntryBook.class);

    void book(Long customerId, long entryId) {
        log.info("Entry booked [customerId={}, entryId={}]", customerId, entryId);
    }
}
