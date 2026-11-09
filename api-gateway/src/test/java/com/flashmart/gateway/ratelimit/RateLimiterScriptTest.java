package com.flashmart.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Our copy of the rate-limiter script against the one inside the Spring Cloud
 * Gateway library, and the script object's fixed values.
 */
class RateLimiterScriptTest {

    /** Where Spring Cloud Gateway keeps its script (GatewayRedisAutoConfiguration). */
    private static final String UPSTREAM = "META-INF/scripts/request_rate_limiter.lua";

    /**
     * Drift guard: a Spring Cloud Gateway upgrade that changes its script must
     * fail here, so our copy is updated on purpose, never left behind silently.
     * Compares the raw bytes, so even a line-ending change counts.
     */
    @Test
    void ourCopyIsByteForByteTheScriptInsideTheGatewayLibrary() throws Exception {
        ClassPathResource upstream = new ClassPathResource(UPSTREAM);
        assertThat(upstream.getURL().toString())
                .as("the upstream script must come from the Spring Cloud Gateway jar, not from our resources")
                .contains("spring-cloud-gateway");
        assertThat(new ClassPathResource(RateLimitConfig.SCRIPT_PATH).getContentAsByteArray())
                .isEqualTo(upstream.getContentAsByteArray());
    }

    /**
     * The SHA1 is what EVALSHA sends; Redis caches the script under the hash of
     * its exact text. Ours must equal what Spring Data Redis computes for the
     * library's own script object, or every call would miss Redis' cache.
     */
    @Test
    void sha1MatchesWhatSpringDataRedisComputesForTheLibraryScript() {
        DefaultRedisScript<List> upstream = new DefaultRedisScript<>();
        upstream.setScriptSource(new ResourceScriptSource(new ClassPathResource(UPSTREAM)));
        upstream.setResultType(List.class);

        PreloadedRedisScript<List> ours = PreloadedRedisScript.fromClasspath(RateLimitConfig.SCRIPT_PATH, List.class);

        assertThat(ours.getSha1()).hasSize(40).isEqualTo(upstream.getSha1());
        assertThat(ours.getScriptAsString()).isEqualTo(upstream.getScriptAsString());
        assertThat(ours.getResultType()).isEqualTo(List.class);
    }

    /** Computed once: repeated calls return the very same objects, no recomputation. */
    @Test
    void valuesAreFixedAtConstruction() {
        PreloadedRedisScript<Long> s = new PreloadedRedisScript<>("return 1", Long.class);
        assertThat(s.getSha1()).isSameAs(s.getSha1()).isEqualTo("e0e1f9fabfc9d4800c877a703b823ac0578ff8db");
        assertThat(s.getScriptAsString()).isSameAs(s.getScriptAsString());
    }

    @Test
    void aBlankScriptIsRejected() {
        assertThatThrownBy(() -> new PreloadedRedisScript<>(" ", Long.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PreloadedRedisScript<>(null, Long.class)).isInstanceOf(IllegalArgumentException.class);
    }
}
