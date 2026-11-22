package com.example.e2e.voucher;

public class VoucherMissing extends RuntimeException {
    public VoucherMissing() {
        super();
    }

    public VoucherMissing(String message) {
        super(message);
    }

    public VoucherMissing(String message, Throwable cause) {
        super(message, cause);
    }
}
