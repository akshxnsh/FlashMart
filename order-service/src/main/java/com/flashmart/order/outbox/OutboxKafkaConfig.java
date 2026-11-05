package com.flashmart.order.outbox;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * The producer the outbox relay publishes with, and Order's two topics, wired
 * by hand (no Boot Kafka auto-configuration).
 *
 * <p>Delivery is AT-LEAST-ONCE. {@code acks=all} and the idempotent producer
 * stop the producer's own retries from writing a record twice, but nothing here
 * makes the relay exactly-once: a crash between the broker's ack and the
 * {@code published_at} commit re-sends the row, and consumers must be
 * idempotent (decisions.md, "Day 6", point 6).
 *
 * <p>The producer's timeouts are all derived from the relay's ack timeout, so a
 * send cannot outlive the wait the relay is prepared to make:
 * {@code max.block.ms} bounds the metadata wait inside {@code send()} (an
 * unreachable broker blocks there, not in the future), and
 * {@code delivery.timeout.ms} bounds retries after it. The relay also caps
 * send + ack together at the ack timeout (see {@link OutboxRelay}).
 */
@Configuration
public class OutboxKafkaConfig {

    /** Producer properties for {@code bootstrap}. Public so tests can build a producer for another address. */
    public static Map<String, Object> producerProperties(String bootstrap, long ackTimeoutMs) {
        int timeout = Math.toIntExact(ackTimeoutMs);
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Explicit, so the send-as-soon-as-possible behaviour does not depend on
        // a client default; and delivery.timeout.ms >= linger.ms + request.timeout.ms
        // is the client's own rule.
        props.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeout);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeout);
        return props;
    }

    @Bean
    public ProducerFactory<String, String> outboxProducerFactory(
            @Value("${kafka.bootstrap}") String bootstrap, RelaySettings settings) {
        return new DefaultKafkaProducerFactory<>(producerProperties(bootstrap, settings.ackTimeoutMs()));
    }

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(ProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }

    /**
     * Creates the topics below at startup, because the docker-compose broker
     * has auto-creation off. {@code kafka.create-topics=false} skips it (the
     * tests that do not use Kafka set that, so no context waits on a broker).
     */
    @Bean
    public KafkaAdmin kafkaAdmin(@Value("${kafka.bootstrap}") String bootstrap,
                                 @Value("${kafka.create-topics}") boolean createTopics) {
        KafkaAdmin admin = new KafkaAdmin(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap));
        admin.setAutoCreate(createTopics);
        return admin;
    }

    /** One partition: key = order id keeps one order's events in order; one partition orders them all. */
    @Bean
    public NewTopic paymentConfirmedTopic() {
        return TopicBuilder.name(OutboxTopics.PAYMENT_CONFIRMED).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return TopicBuilder.name(OutboxTopics.ORDER_CANCELLED).partitions(1).replicas(1).build();
    }
}
