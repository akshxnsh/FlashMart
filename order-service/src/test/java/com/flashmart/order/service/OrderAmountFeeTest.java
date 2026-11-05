package com.flashmart.order.service;

import com.flashmart.order.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The flat fee in the order amount (decisions.md, "Day 5", point 1), on real
 * Postgres. Rolled back. Amounts are read back with native SQL, so the stored
 * value is what is checked, not an entity field.
 */
@SpringBootTest
@Transactional
class OrderAmountFeeTest {

    @Autowired
    private OrderCreationTransaction transaction;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    private long pricedOrder(long buyer, String... unitPrices) {
        java.util.List<OrderLine> lines = new java.util.ArrayList<>();
        for (int i = 0; i < unitPrices.length; i++) {
            lines.add(new OrderLine(11L + i, 1));
        }
        long id = transaction.insert(buyer, 7L, "fee-" + buyer, lines);
        for (int i = 0; i < unitPrices.length; i++) {
            transaction.priceItem(id, 11L + i, "Product", new BigDecimal(unitPrices[i]));
        }
        return id;
    }

    private BigDecimal amount(long id) {
        return jdbc.queryForObject("select amount from orders where id = ?", BigDecimal.class, id);
    }

    private BigDecimal subtotals(long id) {
        return jdbc.queryForObject("select sum(subtotal) from order_items where order_id = ?",
                BigDecimal.class, id);
    }

    @Test
    void theAmountIsTheItemsPlusTheFlatFee() {
        long id = pricedOrder(4201L, "100.00", "25.50");

        assertEquals(1, transaction.setAmountFromItems(id));

        assertEquals(0, new BigDecimal("174.50").compareTo(amount(id))); // 125.50 + 49.00
        // The fee is derivable with no column: amount - SUM(subtotal).
        assertEquals(0, new BigDecimal("49.00").compareTo(amount(id).subtract(subtotals(id))));
    }

    @Test
    void anOrderOfOnlyFreeItemsCostsJustTheFee() {
        long id = pricedOrder(4202L, "0.00", "0.00");

        assertEquals(1, transaction.setAmountFromItems(id));

        assertEquals(0, new BigDecimal("49.00").compareTo(amount(id)));
    }

    @Test
    void aPricedAmountDoesNotChangeWhenTheFeeConfigChangesLater() {
        long id = pricedOrder(4203L, "10.00");
        assertEquals(1, transaction.setAmountFromItems(id));

        CreateOrderSettings dearer = Mockito.mock(CreateOrderSettings.class);
        Mockito.when(dearer.fee()).thenReturn(new BigDecimal("99.00"));
        OrderCreationTransaction later = new OrderCreationTransaction(jdbc, orders, dearer);

        assertEquals(0, later.setAmountFromItems(id)); // write-once CAS: already set
        assertEquals(0, new BigDecimal("59.00").compareTo(amount(id)));
        // And the fee is still readable from the row: 49.00, not 99.00.
        assertEquals(0, new BigDecimal("49.00").compareTo(amount(id).subtract(subtotals(id))));
    }

    @Test
    void theGuardRejectsAZeroOrNegativeAmount() {
        assertThrows(IllegalStateException.class, () -> transaction.requirePayable(1L, new BigDecimal("0.00")));
        assertThrows(IllegalStateException.class, () -> transaction.requirePayable(1L, new BigDecimal("-0.01")));
        assertThrows(IllegalStateException.class, () -> transaction.requirePayable(1L, null));
        transaction.requirePayable(1L, new BigDecimal("0.01")); // does not throw
    }

    @Test
    void aBrokenFeeOfZeroOnFreeItemsFailsLoudlyAndLeavesTheOrderUnpriced() {
        long id = pricedOrder(4204L, "0.00");
        CreateOrderSettings noFee = Mockito.mock(CreateOrderSettings.class);
        Mockito.when(noFee.fee()).thenReturn(BigDecimal.ZERO);
        OrderCreationTransaction broken = new OrderCreationTransaction(jdbc, orders, noFee);

        assertThrows(IllegalStateException.class, () -> broken.setAmountFromItems(id));

        assertNull(amount(id));
    }
}
