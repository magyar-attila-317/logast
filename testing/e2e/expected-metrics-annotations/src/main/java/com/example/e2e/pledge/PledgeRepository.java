package com.example.e2e.pledge;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate repository: an update, an upsert through queryForObject, a list read. */
@Repository
public class PledgeRepository {
    private static final String CLOSE = "UPDATE pledges SET closed = true WHERE pledge_id = ?";
    private static final String DELETE_EXPIRED = "DELETE FROM pledges WHERE closed = true AND closed_at < now() - interval '30 days'";
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
        jdbcTemplate.update(CLOSE, pledgeId);
    }

    public int deleteExpired() {
        return jdbcTemplate.update(DELETE_EXPIRED);
    }

    public int countUse(String pledgeId) {
        return jdbcTemplate.queryForObject(COUNT_USE, Integer.class, pledgeId);
    }

    public List<String> findOpen() {
        return jdbcTemplate.queryForList("SELECT pledge_id FROM pledges WHERE closed = false", String.class);
    }
}
