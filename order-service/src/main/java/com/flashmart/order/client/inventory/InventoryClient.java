package com.flashmart.order.client.inventory;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Inventory's internal reserve endpoint. Called directly, never through the
 * Gateway. Timeouts and the no-retry policy come from
 * {@code spring.cloud.openfeign.client.config.inventory} in application.yml;
 * nothing here relies on Feign's defaults.
 *
 * <p>Use {@link InventoryCalls}, not this interface, from the service layer: it
 * turns every transport failure and non-200 into one exception.
 */
@FeignClient(name = "inventory", url = "${inventory.base-url}", configuration = InventoryClientConfig.class)
public interface InventoryClient {

    @PostMapping("/internal/flash-sale-items/reserve")
    ReserveResponse reserve(@RequestBody ReserveRequest request);
}
