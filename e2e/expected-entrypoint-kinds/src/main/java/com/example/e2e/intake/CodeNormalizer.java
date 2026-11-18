package com.example.e2e.intake;

import com.example.e2e.logging.MdcScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CodeNormalizer {
    public String normalize(String couponId) {
        try (var mdc = MdcScope.of("couponId", couponId)) {
            var result = couponId.trim();
            log.info("Code normalizer completed [trim={}]", result); // logast:wording
            IntakeMetrics.codeNormalizerCompleted(); // logast:metric review, confidence: low
            return result;
        }
    }

    public void archive(String couponId) {
    }
}
