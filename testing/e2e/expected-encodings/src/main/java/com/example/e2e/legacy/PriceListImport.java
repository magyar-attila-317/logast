package com.example.e2e.legacy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Imports price lists (Übersicht). */
@Component
public class PriceListImport {
    private static final Logger log = LoggerFactory.getLogger(PriceListImport.class);

    public void load(String listId) {
        // Preisliste übernehmen: Größe, café, õ (ISO-8859-1)
        log.info("Liste importée [listId={}]", listId);
    }
}
