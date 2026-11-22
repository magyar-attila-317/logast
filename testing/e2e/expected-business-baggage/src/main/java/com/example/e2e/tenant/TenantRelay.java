package com.example.e2e.tenant;

import com.example.e2e.logging.BusinessBaggage;
import org.springframework.stereotype.Component;

@Component
public class TenantRelay {
    public void relay(String tenantId, Runnable downstreamCall) {
        try (var baggage = BusinessBaggage.with("tenantId", tenantId)) {
            downstreamCall.run();
        }
    }
}
