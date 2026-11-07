package com.flashmart.gateway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** {@link GatewayContractTest} with the routes pointing at fixed addresses (the stubs' ports). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayContractTest.FixedClock.class)
class FixedAddressGatewayTest extends GatewayContractTest {

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry r) {
        r.add("flashmart.routes.identity-uri", IDENTITY::uri);
        r.add("flashmart.routes.order-uri", ORDER::uri);
        r.add("flashmart.routes.inventory-uri", INVENTORY::uri);
    }
}
