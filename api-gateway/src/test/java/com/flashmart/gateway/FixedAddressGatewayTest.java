package com.flashmart.gateway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link GatewayContractTest} through the fallback profile {@code fixed}: the
 * routes point at fixed addresses (the stubs' ports), no discovery.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("fixed")
@Import(GatewayContractTest.FixedClock.class)
class FixedAddressGatewayTest extends GatewayContractTest {

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry r) {
        r.add("flashmart.routes.identity-uri", IDENTITY::uri);
        r.add("flashmart.routes.order-uri", ORDER::uri);
        r.add("flashmart.routes.inventory-uri", INVENTORY::uri);
    }
}
