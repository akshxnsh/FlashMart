package com.flashmart.paymentstub;

import tools.jackson.databind.JsonNode;

/**
 * The raw body. {@code amount} is a {@link JsonNode} so a JSON number can be
 * told apart from a JSON string: money crosses the wire as a string, never a float.
 */
public record InitiateRequest(Long orderId, JsonNode amount, String currency) {
}
