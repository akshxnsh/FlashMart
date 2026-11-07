package com.flashmart.identity.auth;

import java.util.Locale;

/**
 * The one email normalisation, used before every insert AND every lookup.
 *
 * <p>{@code users.email} carries {@code CHECK (email = lower(btrim(email)))}
 * and {@code UNIQUE (email)}. If signup stored {@code Priya@X.com} and login
 * looked up {@code priya@x.com}, the buyer could never log in; if the two
 * paths normalised differently, {@code UNIQUE} would let two accounts share
 * one address. So both paths call this.
 */
public final class Emails {

    private Emails() {
    }

    /** Trim, then lower-case with {@link Locale#ROOT} (never the JVM's default locale). Null stays null. */
    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
