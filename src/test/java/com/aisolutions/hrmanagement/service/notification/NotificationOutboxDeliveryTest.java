package com.aisolutions.hrmanagement.service.notification;

import java.util.Map;

import jakarta.inject.Inject;

import com.aisolutions.hrmanagement.testsupport.MySQLTestResource;
import com.aisolutions.hrmanagement.testsupport.NotificationEmailKafkaProbe;
import com.aisolutions.shared.notification.NotificationPublisher;
import com.aisolutions.shared.notification.NotificationTransaction;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.sqlclient.Pool;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage that a committed HR outbox row is relayed to the real Kafka topic, using
 * the Testcontainers schema and broker from {@link MySQLTestResource} rather than a live database.
 */
@QuarkusTest
@QuarkusTestResource(MySQLTestResource.class)
class NotificationOutboxDeliveryTest {

    private static final String TEST_COMPANY_ID = "db_test2";
    private static final String TEST_RECIPIENT = "hr-outbox-e2e@example.com";

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String kafkaBootstrapServers;

    @Inject
    NotificationPublisher notificationPublisher;

    @Inject
    Pool defaultPool;

    @Inject
    ObjectMapper objectMapper;

    /** Stages an email on a real transaction and waits for the relay to publish it. */
    @Test
    void publishesCommittedEmailToKafka() throws java.io.IOException {
        try (NotificationEmailKafkaProbe probe = new NotificationEmailKafkaProbe(kafkaBootstrapServers)) {
            stageTestEmail();
            var record = probe.awaitRecipient(TEST_RECIPIENT);
            var event = objectMapper.readTree(record.value());
            assertThat(record.key()).isEqualTo(TEST_COMPANY_ID);
            assertThat(event.path("companyId").asText()).isEqualTo(TEST_COMPANY_ID);
            assertThat(event.path("notificationId").asText()).isNotBlank();
            assertThat(event.path("templateName").asText()).isEqualTo("hr_claim_submitted_v1");
            assertThat(event.path("languageCode").asText()).isEqualTo("en");
            assertThat(event.path("templateParameters").get("action").asText()).isEqualTo("submitted");
        }
    }

    /** Commits one tenant-scoped email on the default pool's business transaction. */
    private void stageTestEmail() {
        defaultPool
                .withTransaction(transaction -> notificationPublisher.enqueueEmailTemplate(
                        new NotificationTransaction(transaction, TEST_COMPANY_ID),
                        TEST_RECIPIENT,
                        "hr_claim_submitted_v1",
                        "en",
                        Map.of(
                                "claimant_name", "HR outbox delivery test",
                                "claim_period", "2026",
                                "amount", "0.01",
                                "action", "submitted")))
                .await()
                .indefinitely();
    }
}
