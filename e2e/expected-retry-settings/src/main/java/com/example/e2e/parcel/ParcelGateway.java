package com.example.e2e.parcel;

import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

@Component
public class ParcelGateway {
    @Retryable(retryFor = IllegalStateException.class, maxAttemptsExpression = "#{${resilience.retry.spring-retry-defaults.max-attempts}}", backoff = @Backoff(delayExpression = "#{${resilience.retry.spring-retry-defaults.delay}}", multiplierExpression = "#{${resilience.retry.spring-retry-defaults.multiplier}}"))
    public void send(String parcelId) {
        System.out.println(parcelId);
    }

    @Retryable(maxAttemptsExpression = "#{${resilience.retry.usages.parcel-gateway.cancel.max-attempts}}", backoff = @Backoff(delayExpression = "#{${resilience.retry.usages.parcel-gateway.cancel.delay}}"))
    public void cancel(String parcelId) {
        System.out.println(parcelId);
    }
}
