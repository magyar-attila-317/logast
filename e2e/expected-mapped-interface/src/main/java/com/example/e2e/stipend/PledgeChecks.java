package com.example.e2e.stipend;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Checks as nested static classes (the shape real services use), run in order by the calculator. */
public final class PledgeChecks {
    private static final Logger log = LoggerFactory.getLogger(PledgeChecks.class);
    static final String CHECK_LOG_MESSAGE = "Validating pledge [check={}, pledgeId={}, sponsorshipId={}, kioskDay={}]";

    private PledgeChecks() {
    }

    public static List<PledgeCheck> all() {
        return List.of(new ApprovedSponsorshipCheck(), new SubsidyFoundCheck());
    }

    public static class ApprovedSponsorshipCheck implements PledgeCheck {
        @Override
        public Optional<String> validate(PledgeDetails pledge, KioskTotals kioskData) {
            log.trace(CHECK_LOG_MESSAGE, "approved", pledge.pledgeId(), pledge.sponsorshipId(), kioskData.kioskDay());
            if (pledge.sponsorshipId() == null) {
                log.debug("Sponsorship missing");
                return Optional.of("NO_SPONSORSHIP");
            }
            return Optional.empty();
        }
    }

    public static class SubsidyFoundCheck implements PledgeCheck {
        @Override
        public Optional<String> validate(PledgeDetails pledge, KioskTotals kioskData) {
            log.trace(CHECK_LOG_MESSAGE, "subsidy", pledge.pledgeId(), pledge.sponsorshipId(), kioskData.kioskDay());
            if (pledge.subsidyPercentage() == null) {
                log.debug("No subsidy found");
                return Optional.of("NO_SUBSIDY");
            }
            return Optional.empty();
        }
    }
}
