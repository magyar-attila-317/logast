package com.example.e2e.voucher;

final class VoucherErrors {
    private VoucherErrors() {
    }

    static VoucherExpired expired(String voucherId) {
        return new VoucherExpired("voucher expired: " + voucherId);
    }

    static VoucherStoreFailure storeFailed(String voucherId) {
        return new VoucherStoreFailure("voucher not stored: " + voucherId);
    }

    static VoucherStoreFailure storeFailed(String voucherId, Throwable cause) {
        return new VoucherStoreFailure("voucher not stored: " + voucherId, cause);
    }
}
