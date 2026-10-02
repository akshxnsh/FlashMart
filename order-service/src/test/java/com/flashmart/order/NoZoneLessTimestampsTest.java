package com.flashmart.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class NoZoneLessTimestampsTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void everyTimeColumnIsTimestamptz() {
        List<String> offenders = jdbcTemplate.queryForList(
                "select table_name || '.' || column_name from information_schema.columns "
                        + "where table_schema = 'public' and data_type = 'timestamp without time zone' "
                        + "and table_name <> 'flyway_schema_history' order by 1",
                String.class);

        assertTrue(offenders.isEmpty(),
                "zone-less TIMESTAMP columns found, use TIMESTAMPTZ instead: " + offenders);
    }
}
