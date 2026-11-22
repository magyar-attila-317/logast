package com.example.e2e.edge;

import com.example.e2e.stipend.KioskTotals;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A strategy per enum constant. */
public enum Exclusion {
    EXPIRED {
        @Override
        Optional<String> test(KioskTotals kioskData) {
            LOG.debug("Expiry checked [pledgeId={}]", kioskData.pledgeId());
            return Optional.empty();
        }
    },
    UNFUNDED {
        @Override
        Optional<String> test(KioskTotals kioskData) {
            LOG.debug("Subsidy checked [pledgeId={}]", kioskData.pledgeId());
            return Optional.empty();
        }
    };

    private static final Logger LOG = LoggerFactory.getLogger(Exclusion.class);

    abstract Optional<String> test(KioskTotals kioskData);
}
