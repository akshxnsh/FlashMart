package com.flashmart.order.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;

@Configuration
public class GatewayIdentityConfig {

    /** Order's wall clock, used only for the signed header's age. A bean so tests can fix it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** {@code /api/orders/*} matches {@code /api/orders} itself and everything below it. */
    @Bean
    FilterRegistrationBean<GatewayIdentityFilter> gatewayIdentityFilter(GatewayIdentity identity, JsonMapper json) {
        FilterRegistrationBean<GatewayIdentityFilter> registration =
                new FilterRegistrationBean<>(new GatewayIdentityFilter(identity, json));
        registration.addUrlPatterns("/api/orders/*");
        return registration;
    }
}
