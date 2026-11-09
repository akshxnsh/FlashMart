package com.flashmart.inventory.warmup;

import com.flashmart.inventory.service.ReservationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The warm-up is ON here, in a real context with a real web server, and the
 * warm-up that runs at START-UP is the one observed: a post-processor wraps the
 * reserve service when it is created, so it stamps every start-up call, and the
 * web server's initialised event stamps the other side. Every warm-up call must
 * be stamped before the event (the point where the port opens and Eureka / Kafka
 * start).
 *
 * <p>It can only say something if the shared database had a flash sale item at
 * start-up (the warm-up skips an empty one), so it is SKIPPED, visibly, when no
 * call was seen. The dev database is seeded, so here it runs.
 * {@code @TestConfiguration} is not picked up by component scan; a plain
 * {@code @Configuration} in test sources would be (the Day 9 lesson).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "startup-warmup.enabled=true")
@Import(StartupWarmupBeforePortTest.Stamps.class)
class StartupWarmupBeforePortTest {

    static final AtomicLong CALLS = new AtomicLong();
    static final AtomicLong LAST_CALL_NANOS = new AtomicLong();
    static final AtomicLong WEB_SERVER_NANOS = new AtomicLong();

    @TestConfiguration
    static class Stamps {
        @Bean
        static BeanPostProcessor stampReserveCalls() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof ReservationService)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(),
                            new Class<?>[] {ReservationService.class}, (proxy, method, args) -> {
                                if ("reserve".equals(method.getName())) {
                                    CALLS.incrementAndGet();
                                    LAST_CALL_NANOS.set(System.nanoTime());
                                }
                                try {
                                    return method.invoke(bean, args);
                                } catch (InvocationTargetException e) {
                                    throw e.getTargetException();
                                }
                            });
                }
            };
        }

        @Bean
        ApplicationListener<WebServerInitializedEvent> webServerStamp() {
            return event -> WEB_SERVER_NANOS.set(System.nanoTime());
        }
    }

    @Test
    void everyStartUpWarmupCallIsBeforeTheWebServerIsInitialised() {
        assertTrue(WEB_SERVER_NANOS.get() != 0, "the web server event was never seen");
        assumeTrue(CALLS.get() > 0, "no flash sale item in the database at start-up, nothing was warmed");

        assertEquals(20, CALLS.get(), "the configured number of warm-up calls");
        assertTrue(LAST_CALL_NANOS.get() < WEB_SERVER_NANOS.get(),
                "the last warm-up call (" + LAST_CALL_NANOS.get() + ") came after the web server event ("
                        + WEB_SERVER_NANOS.get() + ")");
    }
}
