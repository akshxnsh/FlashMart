package com.flashmart.identity.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

/**
 * bcrypt behind a {@link DelegatingPasswordEncoder} (decisions.md, "Day 8",
 * point 6). Stored hashes look like {@code {bcrypt}$2a$10$...}: the prefix
 * names the algorithm and the hash carries its own cost, so the cost (or the
 * algorithm) can change later without breaking existing passwords.
 */
@Configuration
public class PasswordConfig {

    private static final String BCRYPT = "bcrypt";

    @Bean
    public PasswordEncoder passwordEncoder(@Value("${identity.password.bcrypt-strength}") int strength) {
        DelegatingPasswordEncoder encoder =
                new DelegatingPasswordEncoder(BCRYPT, Map.of(BCRYPT, new BCryptPasswordEncoder(strength)));
        // A stored hash with no {id} prefix (the seed's placeholder hashes)
        // would otherwise make matches() THROW, which login would turn into a
        // 500. Such a hash can never match a password: answer false.
        encoder.setDefaultPasswordEncoderForMatches(new NeverMatches());
        return encoder;
    }

    private static final class NeverMatches implements PasswordEncoder {

        @Override
        public String encode(CharSequence rawPassword) {
            throw new UnsupportedOperationException("only used for matching");
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return false;
        }
    }
}
