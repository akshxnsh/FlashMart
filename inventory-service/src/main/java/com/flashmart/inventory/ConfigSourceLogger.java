package com.flashmart.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.stereotype.Component;

/**
 * One start-up line saying where the tunable values came from: the config
 * server, or this service's own local application.yml (decisions.md, Day 9).
 * It looks for a property source the config-server client added; when the
 * server was down (the import is optional) there is none.
 */
@Component
class ConfigSourceLogger {

    private static final Logger log = LoggerFactory.getLogger(ConfigSourceLogger.class);

    private final ConfigurableEnvironment env;

    ConfigSourceLogger(ConfigurableEnvironment env) {
        this.env = env;
    }

    @EventListener(ApplicationReadyEvent.class)
    void logSource() {
        boolean fromServer = env.getPropertySources().stream()
                .anyMatch(ps -> ps instanceof EnumerablePropertySource && ps.getName().contains("configserver:")
                        && ((EnumerablePropertySource<?>) ps).getPropertyNames().length > 0);
        if (fromServer) {
            log.info("CONFIG SOURCE: config server (values from the config repo, local file is the fallback)");
        } else {
            log.warn("CONFIG SOURCE: local fallback (config server not used; values from this service's application.yml)");
        }
    }
}
