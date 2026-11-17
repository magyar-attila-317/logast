package com.example.e2e.voucher;

public class VoucherUnknown extends RuntimeException {
    public VoucherUnknown() {
        super();
    }

    public VoucherUnknown(String message) {
        super(message);
    }

    public VoucherUnknown(String message, Throwable cause) {
        super(message, cause);
    }
}
