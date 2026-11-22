package com.example.e2e.web;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Named like audit, records nothing (#45): its lines are application lines and keep their logger. */
@RestController
public class AuditController {
    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    @GetMapping("/audit/settings")
    public List<String> settings() {
        log.debug("Audit settings requested");
        return List.of("retention=365d");
    }
}
