package com.example.e2e.shelf;

import com.example.e2e.logging.AfterCommit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class ShelfSlotWriter {
    private static final String ADD_SLOT = "insert into shelf_slot (id, status) values (?, ?)";

    private final JdbcTemplate jdbc;
    private final JdbcClient client;

    public ShelfSlotWriter(JdbcTemplate jdbc, JdbcClient client) {
        this.jdbc = jdbc;
        this.client = client;
    }

    public int block(String slotId) {
        int rows = jdbc.update("update shelf_slot set status = 'BLOCKED' where id = ?", slotId);
        AfterCommit.log(() -> log.info("Shelf slot updated [rows={}]", rows)); // logast:wording
        ShelfMetrics.shelfSlotUpdated(rows);
        return rows;
    }

    public int release(String slotId, String by) {
        int rows = jdbc.update("update shelf_slot set status = 'FREE', last_modified_by = ? where id = ?", by, slotId);
        AfterCommit.log(() -> log.info("Shelf slot updated [rows={}]", rows)); // logast:wording
        ShelfMetrics.shelfSlotUpdated(rows);
        return rows;
    }

    public int add(String slotId) {
        int rows = client.sql(ADD_SLOT).param(slotId).param("FREE").update();
        AfterCommit.log(() -> log.info("Shelf slot inserted [rows={}]", rows)); // logast:wording
        ShelfMetrics.shelfSlotInserted(rows);
        return rows;
    }
}
