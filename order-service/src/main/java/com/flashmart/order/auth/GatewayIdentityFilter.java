package com.flashmart.order.auth;

import com.flashmart.order.dto.ErrorCode;
import com.flashmart.order.dto.OrderErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * Runs {@link GatewayIdentity} on {@code /api/orders/**} only (registered in
 * {@link GatewayIdentityConfig}); not on {@code /webhooks/payment}, which has
 * its own HMAC, and not on {@code /internal/**}, which the Gateway never routes.
 *
 * <p>A filter, not a controller argument, so every endpoint under
 * {@code /api/orders}, present or future, is covered without remembering to
 * ask for it. On success the verified id is stored as the request attribute
 * {@link #VERIFIED_BUYER_ID}, which is the only place the controller reads the
 * buyer from. On failure: {@code 401 UNAUTHORIZED} in Order's error format,
 * before any validation or database work.
 */
public class GatewayIdentityFilter extends OncePerRequestFilter {

    public static final String VERIFIED_BUYER_ID = "com.flashmart.order.verifiedBuyerId";
    static final String BUYER_ID = "X-Buyer-Id";
    static final String TIMESTAMP = "X-Auth-Timestamp";
    static final String SIGNATURE = "X-Auth-Signature";

    private final GatewayIdentity identity;
    private final JsonMapper json;

    public GatewayIdentityFilter(GatewayIdentity identity, JsonMapper json) {
        this.identity = identity;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long buyerId;
        try {
            buyerId = identity.verify(request.getHeader(BUYER_ID), request.getHeader(TIMESTAMP),
                    request.getHeader(SIGNATURE));
        } catch (GatewayIdentity.InvalidIdentityException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            json.writeValue(response.getOutputStream(), OrderErrorResponse.of(ErrorCode.UNAUTHORIZED, e.getMessage()));
            return;
        }
        request.setAttribute(VERIFIED_BUYER_ID, buyerId);
        chain.doFilter(request, response);
    }
}
