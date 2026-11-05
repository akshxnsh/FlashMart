package com.flashmart.inventory.messaging;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * The consumer side, wired by hand like Order's producer (no Boot Kafka
 * auto-configuration). Derived from decisions.md, "Day 7", points 3 and 4.
 *
 * <ul>
 *   <li><b>Offsets:</b> {@code enable.auto.commit=false} and ack mode RECORD:
 *       the offset moves only after the listener method returns, and the
 *       listener returns only after the DB transaction has committed. If the
 *       transaction fails, the offset does not move and the message comes
 *       again. At-least-once, never exactly-once.</li>
 *   <li><b>Malformed message:</b> not retried (it can never succeed). Skipped
 *       with an ERROR log holding the raw message.</li>
 *   <li><b>Any other failure</b> (DB down): retried with growing waits,
 *       forever, one WARN per failure. Nothing else on that partition is
 *       processed meanwhile, which keeps one order's events in order.</li>
 *   <li><b>No dead-letter topic</b> (a recorded limitation). A skipped message
 *       leaves that order's stock RESERVED until fixed by hand; the
 *       stale-reservation reconciler that would catch it is designed, not
 *       built.</li>
 * </ul>
 */
@Slf4j
@EnableKafka
@Configuration
public class InventoryKafkaConfig {

    /** Public so tests can build a consumer with the same settings for another address. */
    public static Map<String, Object> consumerProperties(String bootstrap, String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // A group that has never committed starts at the beginning, so an
        // event sent before Inventory first joined is not skipped. Replays
        // are harmless (status guard).
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    @Bean
    public ConsumerFactory<String, String> inventoryConsumerFactory(
            @Value("${kafka.bootstrap}") String bootstrap,
            @Value("${kafka.consumer.group-id}") String groupId) {
        return new DefaultKafkaConsumerFactory<>(consumerProperties(bootstrap, groupId));
    }

    /**
     * PROVISIONAL, unmeasured: the three backoff numbers are config. No
     * maximum number of attempts or elapsed time, so a transient failure is
     * retried until it clears.
     */
    @Bean
    public DefaultErrorHandler inventoryErrorHandler(
            @Value("${kafka.consumer.backoff-initial-ms}") long initialMs,
            @Value("${kafka.consumer.backoff-multiplier}") double multiplier,
            @Value("${kafka.consumer.backoff-max-ms}") long maxMs) {
        ExponentialBackOff backOff = new ExponentialBackOff(initialMs, multiplier);
        backOff.setMaxInterval(maxMs);
        // The default max elapsed time is already unlimited; stated so it is not an accident.
        backOff.setMaxElapsedTime(Long.MAX_VALUE);

        DefaultErrorHandler handler = new DefaultErrorHandler(InventoryKafkaConfig::skip, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        handler.setCommitRecovered(true);
        handler.setRetryListeners((record, ex, attempt) -> log.warn(
                "Inventory consumer failed on {}-{}@{} (attempt {}), will retry with backoff: {}",
                record.topic(), record.partition(), record.offset(), attempt, rootMessage(ex)));
        return handler;
    }

    /** The recoverer for a skipped message: log it so a human can fix that order's stock. */
    static void skip(ConsumerRecord<?, ?> record, Exception ex) {
        log.error("Skipping malformed message {}-{}@{} (key {}), NOT retried, no dead-letter topic. "
                        + "That order's stock stays RESERVED until fixed by hand. Reason: {}. Raw message: {}",
                record.topic(), record.partition(), record.offset(), record.key(), rootMessage(ex), record.value());
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> inventoryConsumerFactory, DefaultErrorHandler inventoryErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(inventoryConsumerFactory);
        // Offset commit after each listener call returns, i.e. after the DB commit.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(inventoryErrorHandler);
        return factory;
    }
}
