package com.flashmart.identity.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock that stamps {@code iat}/{@code exp} on access tokens. A bean so a
 * test can fix time. Token times are audit/expiry stamps read by the gateway,
 * not compared inside this database, so the Java clock is the right one here
 * (decisions.md, "Who stamps a time column"); refresh-token times are stamped
 * by the database instead.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
