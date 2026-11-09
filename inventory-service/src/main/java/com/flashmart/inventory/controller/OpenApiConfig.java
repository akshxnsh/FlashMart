package com.flashmart.inventory.controller;

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
                .title("FlashMart Inventory API")
                .version("0.0.1")
                .description("Public browse of flash sales. The internal reserve endpoint is hidden from these docs: it is called only by Order Service and never through the Gateway. This page is served on the service's own port and is not routed through the Gateway."));
    }
}
