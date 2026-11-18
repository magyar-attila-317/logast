package com.example.e2e.stipend;

import com.example.e2e.logging.MdcScope;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Static calculator called per row; the matched pledge (same id, other source) is evaluated in a loop. */
public final class StipendCalculator {
    private static final Logger log = LoggerFactory.getLogger(StipendCalculator.class);

    private StipendCalculator() {
    }

    public static StipendOutcome calculate(KioskTotals kioskData, List<PledgeDetails> matchingPledges) {
        log.debug("Calculating stipend [sale.day={}, matching.pledges={}]", kioskData.kioskDay(), matchingPledges.size());
        if (matchingPledges.isEmpty()) {
            log.debug("Pledge not found in the pledge list");
            return new StipendOutcome(true, BigDecimal.ZERO);
        }
        return evaluateOutcome(kioskData, matchingPledges);
    }

    private static StipendOutcome evaluateOutcome(KioskTotals kioskData, List<PledgeDetails> sortedPledges) {
        for (PledgeDetails pledge : sortedPledges) {
            Optional<String> exclusion = validatePledge(kioskData, pledge);
            if (exclusion.isEmpty()) {
                return calculateStipend(kioskData, pledge);
            }
            log.trace("Pledge excluded [reason={}]", exclusion.get());
        }
        return new StipendOutcome(true, BigDecimal.ZERO);
    }

    private static Optional<String> validatePledge(KioskTotals kioskData, PledgeDetails pledge) {
        try (var mdc = MdcScope.of("sponsorship.id", pledge.sponsorshipId())) {
            for (PledgeCheck check : PledgeChecks.all()) {
                Optional<String> result = check.validate(pledge, kioskData);
                if (result.isPresent()) {
                    return result;
                }
            }
            return Optional.empty();
        }
    }

    private static StipendOutcome calculateStipend(KioskTotals kioskData, PledgeDetails pledge) {
        BigDecimal result = kioskData.totalDiscount().multiply(pledge.subsidyPercentage());
        log.debug("Stipend calculated [sale.day={}, result={}]", kioskData.kioskDay(), result);
        return new StipendOutcome(false, result);
    }
}
