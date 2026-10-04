package com.aisolutions.hrmanagement.testsupport;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

/** Observes the real broker for email requests published by the HR outbox relay. */
public final class NotificationEmailKafkaProbe implements AutoCloseable {

    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final KafkaConsumer<String, String> consumer;

    /** Creates an independent consumer and subscribes to the email delivery topic. */
    public NotificationEmailKafkaProbe(String bootstrapServers) {
        consumer = new KafkaConsumer<>(consumerProperties(bootstrapServers));
        consumer.subscribe(List.of("notifications.email.v1"));
    }

    /** Waits for the recipient's event and returns its broker record. */
    public ConsumerRecord<String, String> awaitRecipient(String recipient) {
        long deadline = System.nanoTime() + DELIVERY_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                if (matchesRecipient(record, recipient)) {
                    return record;
                }
            }
        }
        throw new IllegalStateException("HR did not publish the expected email to Kafka");
    }

    /** Releases the consumer and its broker connections. */
    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(5));
    }

    /** Matches JSON recipients without accepting records for other resource tests. */
    private boolean matchesRecipient(ConsumerRecord<String, String> record, String recipient) {
        try {
            JsonNode event = OBJECT_MAPPER.readTree(record.value());
            return recipient.equals(event.path("recipient").asText());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("HR produced invalid notification JSON", failure);
        }
    }

    /** Configures an isolated earliest-offset group with explicit standard string deserializers. */
    private Properties consumerProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put("bootstrap.servers", bootstrapServers);
        properties.put("group.id", "hr-notification-e2e-" + UUID.randomUUID());
        properties.put("auto.offset.reset", "earliest");
        properties.put("enable.auto.commit", "false");
        properties.put("key.deserializer", StringDeserializer.class.getName());
        properties.put("value.deserializer", StringDeserializer.class.getName());
        return properties;
    }
}
