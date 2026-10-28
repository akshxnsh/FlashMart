package com.flashmart.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Inserted up front and unpriced; {@code productName}, {@code unitPrice} and
 * {@code subtotal} are filled only when that item's {@code reserve()} returns.
 * V3's all-or-none {@code CHECK} is what stops a half-filled row.
 *
 * <p>No timestamps: an item has no lifecycle of its own, and the order's own
 * {@code created_at}/{@code updated_at} already bound its two writes.
 * architecture.md section 4 lists exactly these seven columns.
 */
@Entity
@Table(name = "order_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_order_items_order_id_product_id", columnNames = {"order_id", "product_id"})
})
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * A real foreign key in the database, but a plain {@code Long} here: no
     * {@code @ManyToOne}, so nothing lazy-loads an order while iterating items.
     */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** Inventory owns this row, so no foreign key. */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /**
     * Snapshotted at reservation time and never resynced — a receipt shows
     * what was bought, not what the product is called today. That permanence
     * is why V3 guards it against blanks as well as nulls.
     */
    @Column(name = "product_name")
    private String productName;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    /**
     * Stored, not computed, so V3's {@code subtotal = unit_price * quantity}
     * is the only thing stopping it disagreeing with its own inputs. The sum
     * of these is what the buyer is charged.
     */
    @Column(name = "subtotal", precision = 14, scale = 2)
    private BigDecimal subtotal;
}
