package com.flashmart.inventory.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class TimeZoneConsistencyTest {

    private static final List<String> ZONES =
            List.of("Asia/Kolkata", "UTC", "America/Los_Angeles", "Pacific/Auckland");

    @Autowired
    private EntityManager entityManager;

    private void useConnectionTimeZone(String zone) {
        entityManager.createNativeQuery("select set_config('TimeZone', :zone, true)")
                .setParameter("zone", zone)
                .getSingleResult();
    }

    private boolean queryAsBoolean(String sql) {
        return (Boolean) entityManager.createNativeQuery(sql).getSingleResult();
    }

    @Test
    void aZoneLessColumnGivesDifferentAnswersOnDifferentConnections() {
        entityManager.createNativeQuery("create temporary table tz_probe (t timestamp) on commit drop")
                .executeUpdate();
        entityManager.createNativeQuery(
                        "insert into tz_probe select (now() at time zone 'Asia/Kolkata') - interval '1 hour'")
                .executeUpdate();

        useConnectionTimeZone("Asia/Kolkata");
        boolean seenFromKolkata = queryAsBoolean("select t <= now() from tz_probe");
        useConnectionTimeZone("UTC");
        boolean seenFromUtc = queryAsBoolean("select t <= now() from tz_probe");

        assertTrue(seenFromKolkata);
        assertNotEquals(seenFromKolkata, seenFromUtc,
                "the control no longer reproduces the bug, so the test below proves nothing");
    }

    @Test
    void aSaleIsOpenFromEveryConnectionRegardlessOfItsTimeZone() {
        FlashSale sale = new FlashSale();
        sale.setBrandId(1L);
        sale.setName("Zone Probe");
        sale.setStartTime(Instant.now().minus(1, ChronoUnit.HOURS));
        sale.setEndTime(Instant.now().plus(1, ChronoUnit.HOURS));
        sale.setStatus(FlashSaleStatus.ACTIVE);
        entityManager.persist(sale);
        entityManager.flush();

        for (String zone : ZONES) {
            useConnectionTimeZone(zone);
            boolean open = queryAsBoolean(
                    "select start_time <= now() and now() < end_time from flash_sales where id = " + sale.getId());
            assertTrue(open, "sale read as not open from a " + zone + " connection");
        }
    }

    @Test
    void aSaleThatHasNotStartedIsClosedFromEveryConnectionRegardlessOfItsTimeZone() {
        FlashSale sale = new FlashSale();
        sale.setBrandId(1L);
        sale.setName("Future Probe");
        sale.setStartTime(Instant.now().plus(1, ChronoUnit.HOURS));
        sale.setEndTime(Instant.now().plus(3, ChronoUnit.HOURS));
        sale.setStatus(FlashSaleStatus.ACTIVE);
        entityManager.persist(sale);
        entityManager.flush();

        for (String zone : ZONES) {
            useConnectionTimeZone(zone);
            boolean open = queryAsBoolean(
                    "select start_time <= now() and now() < end_time from flash_sales where id = " + sale.getId());
            assertEquals(false, open, "sale read as open from a " + zone + " connection");
        }
    }
}
