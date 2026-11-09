package com.flashmart.gateway.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A Redis script whose text and SHA1 are fixed when it is built: every getter
 * just returns a field (decisions.md, "Day 9: the Gateway's own rate-limiter
 * script").
 *
 * <p>Why not {@code DefaultRedisScript}: its {@code getSha1()} takes one shared
 * lock on EVERY call and, inside it, asks the script source whether the file
 * changed. For a script inside a jar that check throws or opens a jar URL. At
 * the Gateway's event loops that lock was seen holding up to 5 of 8 threads at
 * once (docs/testing.md, "Day 9: the decisive R2 run"). A static script source
 * would still take the lock, so this class implements the interface directly.
 *
 * <p>Thread safety: all fields are final and set in the constructor, so the
 * object is safely shared by every event loop without locking.
 */
public final class PreloadedRedisScript<T> implements RedisScript<T> {

    private final String script;
    private final String sha1;
    private final Class<T> resultType;

    public PreloadedRedisScript(String script, Class<T> resultType) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("script must not be blank");
        }
        this.script = script;
        // The SHA1 must be of the exact text: Redis caches a script under that
        // hash, and EVALSHA with any other value would miss every time.
        this.sha1 = sha1Hex(script);
        this.resultType = resultType;
    }

    /** Reads a classpath resource ONCE (at start-up), as UTF-8. */
    public static <T> PreloadedRedisScript<T> fromClasspath(String path, Class<T> resultType) {
        try {
            return new PreloadedRedisScript<>(
                    new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8), resultType);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read Redis script " + path, e);
        }
    }

    static String sha1Hex(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by every Java platform", e);
        }
    }

    @Override
    public String getSha1() {
        return sha1;
    }

    @Override
    public Class<T> getResultType() {
        return resultType;
    }

    @Override
    public String getScriptAsString() {
        return script;
    }
}
