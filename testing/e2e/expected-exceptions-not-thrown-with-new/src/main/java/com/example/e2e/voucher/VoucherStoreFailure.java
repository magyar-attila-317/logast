package com.example.e2e.voucher;

public class VoucherStoreFailure extends RuntimeException {
    public VoucherStoreFailure() {
        super();
    }

    public VoucherStoreFailure(String message) {
        super(message);
    }

    public VoucherStoreFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
