package com.flashmart.identity.auth;

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
                .title("FlashMart Identity API")
                .version("0.0.1")
                .description("Signup, login, token refresh and logout. All four routes are public (no token needed). Reached by buyers through the Gateway at :8080; this page is served on the service's own port and is not routed through the Gateway."));
    }
}
