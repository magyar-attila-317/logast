package com.example.e2e.voucher;

import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class VoucherController {
    private final VoucherStore store;

    VoucherController(VoucherStore store) {
        this.store = store;
    }

    @GetMapping("/vouchers/{voucherId}")
    String find(@PathVariable String voucherId) {
        return store.findById(voucherId).orElseThrow(() -> new VoucherMissing(voucherId));
    }

    @GetMapping("/vouchers/{voucherId}/code")
    String code(@PathVariable String voucherId) {
        return store.findCode(voucherId).orElseThrow(VoucherUnknown::new);
    }

    @GetMapping("/vouchers/{voucherId}/archived")
    String archived(@PathVariable String voucherId) {
        return store.findById(voucherId).orElseThrow(() -> new VoucherGone(voucherId));
    }

    @PostMapping("/vouchers/{voucherId}/allot")
    void allot(@PathVariable String voucherId) {
        if (store.expired(voucherId)) {
            throw VoucherErrors.expired(voucherId);
        }
    }

    @PostMapping("/vouchers/{voucherId}/store")
    void store(@PathVariable String voucherId) {
        try {
            store.write(voucherId);
        } catch (IOException e) {
            throw VoucherErrors.storeFailed(voucherId);
        }
    }

    @PostMapping("/vouchers/{voucherId}/restore")
    void restore(@PathVariable String voucherId) {
        try {
            store.write(voucherId);
        } catch (IOException | RuntimeException e) {
            throw VoucherErrors.storeFailed(voucherId, e);
        }
    }
}
