package com.flashmart.order.controller;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API title and description for Swagger UI. Docs only; adds no security rule. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("FlashMart Order API")
                .version("0.0.1")
                .description("Order placement. POST /api/orders needs a Gateway-signed identity: X-Buyer-Id plus X-Auth-Timestamp and X-Auth-Signature, produced only by the Gateway after it checks the buyer's JWT. A caller without a valid signature gets 401, so Try it out on this page will get 401 unless you send headers signed with the shared secret; the check is deliberately not relaxed for the docs. The internal order-status endpoint and the payment webhook are hidden from these docs."));
    }
}
