package com.example.e2e.pledge;

import com.example.e2e.logging.MdcScope;
import com.example.e2e.logging.Outcomes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Two shapes whose catch line named the wrong counter: a unique-key race the catch recovers from is no failure, and
 * the failure line in its fallback lambda is counted there, not for every race (#337);
 * a line that starts with the reaction ("Refusing …") names the operation that failed, not the reaction (#336).
 */
@Service
public class PledgeRegistration {
    private static final Logger log = LoggerFactory.getLogger(PledgeRegistration.class);
    private static final String INSERT = "INSERT INTO pledge_registrations (pledge_id) VALUES (?)";
    private final JdbcTemplate jdbcTemplate;
    private final PledgeLookup lookup;

    public PledgeRegistration(JdbcTemplate jdbcTemplate, PledgeLookup lookup) {
        this.jdbcTemplate = jdbcTemplate;
        this.lookup = lookup;
    }

    /** Two requests may register the same pledge at once: the unique key decides, the loser keeps the winner's row. */
    public boolean register(String pledgeId) {
        try {
            jdbcTemplate.update(INSERT, pledgeId);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("Registration insert lost a race; treating as already registered");
            lookup.cached(pledgeId).ifPresentOrElse(id -> lookup.find(id),
                    () -> log.warn("Registration insert failed ({}) and no registration exists", e.getClass().getSimpleName()));
            return false;
        }
    }

    public boolean admit(String pledgeId) {
        try {
            try (var mdc = MdcScope.of("pledgeId", pledgeId)) {
                return lookup.find(pledgeId) != null;
            }
        } catch (PledgeMissing e) {
            log.warn("Refusing pledge admission: session lookup failed [pledgeId={}]", pledgeId, e);
            Outcomes.count("session.lookup", "failed");
            return false;
        }
    }
}
