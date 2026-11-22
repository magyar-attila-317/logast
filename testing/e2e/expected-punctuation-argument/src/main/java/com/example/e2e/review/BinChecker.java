package com.example.e2e.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class BinChecker {
    private static final Logger log = LoggerFactory.getLogger(BinChecker.class);

    void check(String binCode) {
        log.info("Bin checked [binCode={}, value={}]", binCode, "()");
    }

    void empty(char marker) {
        log.info("Bin emptied [value={}]", '-');
    }
}
