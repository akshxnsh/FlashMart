package com.flashmart.order.client.inventory;

import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one place the reserve loop talks to Inventory. Every way of not getting
 * a 200 body becomes {@link InventoryUnavailableException}:
 * <ul>
 *   <li>{@link RetryableException}: connect or read timeout, refused connection.
 *       No retry happens (the client is configured with {@code NEVER_RETRY}).</li>
 *   <li>any other {@link FeignException}: a non-2xx answer, or a body that
 *       cannot be decoded.</li>
 * </ul>
 * A 200 with a business "no" (OUT_OF_STOCK and so on) is not an exception; the
 * caller branches on the outcome.
 */
@Component
@RequiredArgsConstructor
public class InventoryCalls {

    private final InventoryClient client;

    public ReserveResponse reserve(ReserveRequest request) {
        try {
            ReserveResponse response = client.reserve(request);
            if (response == null || response.outcome() == null) {
                throw new InventoryUnavailableException("reserve returned no outcome", null);
            }
            if (response.outcome() == ReserveOutcome.RESERVED
                    && (response.salePrice() == null || response.productName() == null)) {
                // Inventory's contract always sends both with RESERVED. Without
                // them the item cannot be priced, so this is not a usable answer.
                throw new InventoryUnavailableException("RESERVED without salePrice or productName", null);
            }
            return response;
        } catch (RetryableException e) {
            throw new InventoryUnavailableException("inventory unreachable: " + e.getMessage(), e);
        } catch (FeignException e) {
            throw new InventoryUnavailableException("inventory answered " + e.status() + ": " + e.getMessage(), e);
        }
    }
}
