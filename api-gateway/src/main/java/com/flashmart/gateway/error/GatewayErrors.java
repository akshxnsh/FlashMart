package com.flashmart.gateway.error;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Writes the Gateway's error body, {@code {"code": ..., "message": ...}}, the
 * same shape as every service's errors. {@code code} is the contract;
 * {@code message} is a fixed sentence chosen here, never text taken from a
 * request, a token or an exception.
 */
public final class GatewayErrors {

    private GatewayErrors() {
    }

    public static Mono<Void> write(ServerHttpResponse response, HttpStatus status, String code, String message) {
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private static String escape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
