package com.example.e2e.parcel;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
public class ParcelClient {
    @Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}", delayString = "${resilience.retry.defaults.delay}")
    public String track(String parcelId) {
        return "in transit";
    }

    @Retryable(maxRetriesString = "${resilience.retry.defaults.max-retries}", delayString = "${resilience.retry.defaults.delay}")
    public String quote(String parcelId) {
        return "4.20";
    }

    @Retryable(value = IOException.class, timeUnit = TimeUnit.SECONDS, maxRetriesString = "${resilience.retry.usages.parcel-client.label.max-retries}", delayString = "${resilience.retry.usages.parcel-client.label.delay}", multiplierString = "${resilience.retry.usages.parcel-client.label.multiplier}")
    public String label(String parcelId) throws IOException {
        return "label";
    }

    @Retryable(maxRetriesString = "${parcel.audit.retries:1}")
    public String audit(String parcelId) {
        return "audited";
    }
}
