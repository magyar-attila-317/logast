package com.example.e2e.edge;

import com.example.e2e.income.SalesTotals;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A strategy per enum constant. */
public enum Exclusion {
    EXPIRED {
        @Override
        Optional<String> test(SalesTotals salesData) {
            LOG.debug("Expiry checked");
            return Optional.empty();
        }
    },
    UNFUNDED {
        @Override
        Optional<String> test(SalesTotals salesData) {
            LOG.debug("Funding checked");
            return Optional.empty();
        }
    };

    private static final Logger LOG = LoggerFactory.getLogger(Exclusion.class);

    abstract Optional<String> test(SalesTotals salesData);
}
