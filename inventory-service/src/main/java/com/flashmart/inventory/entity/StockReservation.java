package com.flashmart.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;

@Entity
@Table(name = "stock_reservations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_reservations_order_id_item_id", columnNames = {"order_id", "flash_sale_item_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "flash_sale_item_id", nullable = false)
    private Long flashSaleItemId;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StockReservationStatus status;

    /**
     * Stamped by the database (DEFAULT now()), because the stale-reservation
     * reconciler compares it against the database's own now().
     *
     * <p>{@code insertable = false} keeps the column out of the INSERT so the
     * DEFAULT applies — Hibernate would otherwise send an explicit NULL, which
     * beats a DEFAULT. {@code @Generated} is the other half: without it
     * Hibernate never reads the value back, so this field stays null on the
     * instance that was just persisted while the row holds a real timestamp.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "reserved_at", nullable = false, insertable = false, updatable = false)
    private Instant reservedAt;
}
