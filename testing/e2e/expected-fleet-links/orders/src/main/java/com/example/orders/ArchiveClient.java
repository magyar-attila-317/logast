package com.example.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
class ArchiveClient {
    private final RestTemplate restTemplate;
    private final String archiveUrl;

    ArchiveClient(RestTemplateBuilder builder, @Value("${archive.url:http://archive-service}") String archiveUrl) {
        this.restTemplate = builder.build();
        this.archiveUrl = archiveUrl;
    }

    String archived(long id) {
        return restTemplate.getForObject(archiveUrl + "/entries/" + id, String.class);
    }
}
