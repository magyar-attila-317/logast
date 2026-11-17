package com.example.e2e.voucher;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.GONE)
public class VoucherGone extends RuntimeException {
    public VoucherGone(String message) {
        super(message);
    }
}
