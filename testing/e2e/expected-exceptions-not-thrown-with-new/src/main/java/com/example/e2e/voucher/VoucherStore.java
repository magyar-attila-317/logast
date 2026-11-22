package com.example.e2e.voucher;

import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class VoucherStore {
    Optional<String> findById(String voucherId) {
        return Optional.empty();
    }

    Optional<String> findCode(String voucherId) {
        return Optional.empty();
    }

    boolean expired(String voucherId) {
        return voucherId.isEmpty();
    }

    void write(String voucherId) throws IOException {
    }
}
