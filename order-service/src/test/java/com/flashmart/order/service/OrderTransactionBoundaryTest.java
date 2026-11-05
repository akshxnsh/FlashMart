package com.flashmart.order.service;

import com.flashmart.order.TestRows;
import com.flashmart.order.client.inventory.InventoryCalls;
import com.flashmart.order.client.inventory.ReserveOutcome;
import com.flashmart.order.client.inventory.ReserveResponse;
import com.flashmart.order.dto.CreateOrderRequest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Two transaction-boundary claims of the create path, proved rather than
 * reasoned:
 * <ol>
 *   <li>the fail CAS and its outbox row share ONE transaction: if the
 *       surrounding transaction rolls back, neither survives;</li>
 *   <li>no transaction and no pooled connection is held while Inventory is
 *       being called.</li>
 * </ol>
 * Test 2 calls the service directly, not through an HTTP request, so it cannot
 * see anything open-in-view would hold for the length of a web request.
 *
 * <p>NOT {@code @Transactional}: the tests need real commits and rollbacks.
 * Buyers 8000..8099 belong to this class.
 */
@SpringBootTest
class OrderTransactionBoundaryTest {

    private static final long FROM = 8000, TO = 8099;
    private static final long SALE = 7L;

    @Autowired
    private OrderCreationTransaction transaction;

    @Autowired
    private OrderCreationService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private InventoryCalls inventory;

    @AfterEach
    void cleanUp() {
        TestRows.deleteOrdersOfBuyers(jdbc, FROM, TO);
    }

    @Test
    void theFailCasAndItsOutboxRowRollBackTogether() {
        long buyer = FROM;
        long id = jdbc.queryForObject("insert into orders (buyer_id, flash_sale_id, status, idempotency_key, "
                + "expires_at) values (?, ?, 'CREATED', 'k1', clock_timestamp() + interval '1 minute') returning id",
                Long.class, buyer, SALE);

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).execute(tx -> {
            assertTrue(transaction.fail(id, buyer, "x"));
            throw new IllegalStateException("roll the surrounding transaction back");
        }));

        Map<String, Object> order = jdbc.queryForMap("select status, failure_detail from orders where id = ?", id);
        assertEquals("CREATED", order.get("status"));
        assertNull(order.get("failure_detail"));
        assertEquals(0L, jdbc.queryForObject("select count(*) from outbox where aggregate_id = ?", Long.class, id));
    }

    @Test
    void noTransactionAndNoConnectionIsHeldWhileInventoryIsCalled() {
        List<Boolean> transactionActive = new CopyOnWriteArrayList<>();
        List<Integer> activeConnections = new CopyOnWriteArrayList<>();
        HikariDataSource hikari = (HikariDataSource) dataSource;
        doAnswer(inv -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            activeConnections.add(hikari.getHikariPoolMXBean().getActiveConnections());
            return new ReserveResponse(ReserveOutcome.RESERVED, 1L, new BigDecimal("10.00"), 3L, "Product");
        }).when(inventory).reserve(any());

        service.create(FROM + 1, "k1", new CreateOrderRequest(SALE, List.of(
                new CreateOrderRequest.Item(11L, 1), new CreateOrderRequest.Item(12L, 1))));

        assertEquals(2, transactionActive.size()); // one observation per item
        transactionActive.forEach(active -> assertFalse(active, "a transaction was open during a reserve call"));
        activeConnections.forEach(n -> assertEquals(0, n, "a pooled connection was held during a reserve call"));
    }
}
