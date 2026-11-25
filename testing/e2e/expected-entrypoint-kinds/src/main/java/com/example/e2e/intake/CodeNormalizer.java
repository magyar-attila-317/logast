package com.example.e2e.intake;

import com.example.e2e.logging.MdcScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CodeNormalizer {
    public String normalize(String pledgeId) {
        try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
            var result = pledgeId.trim();
            log.info("Code normalizer completed [trim={}]", result); // logast:wording
            IntakeMetrics.codeNormalizerCompleted();
            return result;
        }
    }

    public void archive(String pledgeId) {
    }
}
