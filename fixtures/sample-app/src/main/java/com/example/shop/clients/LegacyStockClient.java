package com.example.shop.clients;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class LegacyStockClient {
    private final RestTemplate rest = new RestTemplate(); // expect: HTTP_CLIENT_UNTRACED

    public String stock(String sku) {
        return rest.getForObject("http://stock-service/items/{sku}", String.class, sku);
    }
}
