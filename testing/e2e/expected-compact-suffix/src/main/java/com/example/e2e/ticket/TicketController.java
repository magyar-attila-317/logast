package com.example.e2e.ticket;

import com.example.e2e.logging.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class TicketController {
    private static final Logger log = LoggerFactory.getLogger(TicketController.class);

    @PostMapping("/tickets/{pledgeId}/close")
    void close(@PathVariable String pledgeId, @RequestParam String channel) {
        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
            log.info("Pledge closed [channel={}]", channel);
        }
    }
}
