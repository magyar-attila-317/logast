package com.example.e2e.parcel;

public class ParcelLost extends RuntimeException {
    public ParcelLost(String message) {
        super(message);
    }
}
