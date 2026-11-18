package com.example.e2e.carrier;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class CarrierLabels {
    @Async
    public void print(String parcelId) {
        System.out.println(parcelId);
    }

    @Async("labelExecutor")
    public void reprint(String parcelId) {
        System.out.println(parcelId);
    }
}
