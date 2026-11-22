package com.example.e2e.voucher;

public class VoucherExpired extends RuntimeException {
    public VoucherExpired() {
        super();
    }

    public VoucherExpired(String message) {
        super(message);
    }

    public VoucherExpired(String message, Throwable cause) {
        super(message, cause);
    }
}
