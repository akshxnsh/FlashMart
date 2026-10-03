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
 * Notification's own copy of a buyer's contact address. Identical in shape to
 * {@link BrandContact}, kept separate because a brand id and a user id are
 * different things that happen to share a number.
 */
@Entity
@Table(name = "buyer_contacts")
@Getter
@Setter
@NoArgsConstructor
public class BuyerContact {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "email", nullable = false)
    private String email;

    /** Identity's timestamp, carried on the event — not when this row was written. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
