package com.flashmart.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Locale;

/**
 * Notification's own copy of a brand's contact address, so sending a receipt
 * never requires Identity to be up. The id comes from Identity and is the
 * primary key: there is exactly one row per brand.
 */
@Entity
@Table(name = "brand_contacts")
@Getter
@Setter
@NoArgsConstructor
public class BrandContact {

    @Id
    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    @Column(name = "email", nullable = false)
    private String email;

    /** Identity's timestamp, carried on the event — not when this row was written. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
