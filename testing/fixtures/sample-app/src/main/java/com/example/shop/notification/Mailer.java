package com.example.shop.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    public void send(String email, String body) {
        log.info("Sending mail to {}", email); // expect: PII_IN_LOG, LOG_FORMAT
    }

    public void bounced(String email) {
        com.example.shop.support.LogHelper.logMaskedEmail(log, "Mail bounced", email);
    }
}
