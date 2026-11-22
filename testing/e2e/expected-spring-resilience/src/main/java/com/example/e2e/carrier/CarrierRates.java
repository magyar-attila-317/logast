package com.example.e2e.carrier;

import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Slf4j
@Repository
public class CarrierRates {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public CarrierRates(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    public Integer rate(String zone) {
        Integer result = jdbc.queryForObject("SELECT rate FROM carrier_rate WHERE zone = ?", Integer.class, zone);
        log.debug("Carrier rate read [value={}]", result); // logast:wording
        return result;
    }
}
