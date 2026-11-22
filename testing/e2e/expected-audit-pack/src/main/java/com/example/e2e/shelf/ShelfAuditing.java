package com.example.e2e.shelf;

import java.util.Optional;
import org.springframework.boot.actuate.audit.InMemoryAuditEventRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.AuditorAware;

@Configuration
public class ShelfAuditing {
    @Bean
    AuditorAware<String> shelfAuditor() {
        return () -> Optional.of("system");
    }

    /** Boot records logins and denied access here: in memory, bounded, gone on restart. */
    @Bean
    InMemoryAuditEventRepository auditEvents() {
        return new InMemoryAuditEventRepository();
    }

    /** Only for local runs: what Boot recommends it for. */
    @Bean
    @Profile("local")
    InMemoryAuditEventRepository localAuditEvents() {
        return new InMemoryAuditEventRepository();
    }
}
