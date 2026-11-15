package com.example.e2e.pledge;

import com.example.e2e.logging.AfterCommit;
import com.example.e2e.logging.Outcomes;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate repository: an update, an upsert through queryForObject, a list read. */
@Slf4j
@Repository
public class PledgeRepository {
    private static final String CLOSE = "UPDATE pledges SET closed = true WHERE pledge_id = ?";
    private static final String COUNT_USE = """
            INSERT INTO pledge_usage (pledge_id, uses) VALUES (?, 1)
            ON CONFLICT (pledge_id) DO UPDATE SET uses = pledge_usage.uses + 1
            RETURNING uses
            """;
    private final JdbcTemplate jdbcTemplate;

    public PledgeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void close(String pledgeId) {
        int rows = jdbcTemplate.update(CLOSE, pledgeId);
        AfterCommit.log(() -> log.debug("Pledges updated [rows={}]", rows)); // logast:wording
        Outcomes.record("pledges", "updated", "rows", rows);
    }

    public int countUse(String pledgeId) {
        int uses = jdbcTemplate.queryForObject(COUNT_USE, Integer.class, pledgeId);
        AfterCommit.log(() -> log.info("Pledge usage saved [uses={}]", uses)); // logast:wording
        Outcomes.count("pledge.usage", "saved", "uses", uses);
        return uses;
    }

    public List<String> findOpen() {
        List<String> result = jdbcTemplate.queryForList("SELECT pledge_id FROM pledges WHERE closed = false", String.class);
        log.debug("Pledges read [count={}]", result.size()); // logast:wording
        return result;
    }
}
