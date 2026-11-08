package com.flashmart.gateway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link GatewayContractTest} through the default {@code lb://} routes. The
 * service ids resolve through static discovery (SimpleDiscoveryClient) to the
 * stubs, so the load-balancer path the real Eureka set-up uses is exercised,
 * without a Eureka server. Every commit-1 assertion runs again here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GatewayContractTest.FixedClock.class)
class DiscoveryGatewayTest extends GatewayContractTest {

    @DynamicPropertySource
    static void instances(DynamicPropertyRegistry r) {
        r.add("spring.cloud.discovery.client.simple.instances.identity-service[0].uri", IDENTITY::uri);
        r.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri", ORDER::uri);
        r.add("spring.cloud.discovery.client.simple.instances.inventory-service[0].uri", INVENTORY::uri);
    }
}
