package com.example.e2e.shelf;

import com.example.lib.shelf.ApiController;
import com.example.lib.shelf.ShelfApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApiController
public class ShelfController implements ShelfApi {
    private static final Logger log = LoggerFactory.getLogger(ShelfController.class);

    @Override
    public String shelf(String shelfId) {
        log.debug("Shelf requested");
        return "shelf";
    }

    @Override
    public String restock(String shelfId) {
        log.debug("Shelf restocked");
        return "restocked";
    }
}
