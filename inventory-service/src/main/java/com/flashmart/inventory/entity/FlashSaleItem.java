package com.flashmart.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "flash_sale_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_flash_sale_items_sale_id_product_id", columnNames = {"flash_sale_id", "product_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FlashSaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flash_sale_id", nullable = false)
    private Long flashSaleId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "sale_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(name = "total_stock", nullable = false)
    private Integer totalStock;

    @Column(name = "available_stock", nullable = false)
    private Integer availableStock;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
