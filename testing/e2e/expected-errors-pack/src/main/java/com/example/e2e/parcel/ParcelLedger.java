package com.example.e2e.parcel;

import java.io.IOException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ParcelLedger {
    @Transactional
    public void archive(String parcelCode) throws IOException {
        if (parcelCode.isBlank()) {
            throw new IOException("no parcel code");
        }
    }

    @Transactional(rollbackFor = IOException.class)
    public void restore(String parcelCode) throws IOException {
        if (parcelCode.isBlank()) {
            throw new IOException("no parcel code");
        }
    }
}
