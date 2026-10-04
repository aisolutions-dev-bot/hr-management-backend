package com.aisolutions.hrmanagement.testsupport;

import java.util.LinkedHashMap;
import java.util.Map;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts an ephemeral MySQL container per test run, seeded from {@code schema.sql} (a verbatim
 * capture of the real company-schema DDL), and points {@code quarkus.datasource.reactive.*} at
 * it before the app boots. {@link com.aisolutions.shared.tenancy.CompanyPoolManager} builds its
 * pools from those same properties, so every repository call in a {@code QuarkusTest} lands on
 * this container — the live/production database is never contacted.
 *
 * <p>The notification channels are pinned per channel rather than through
 * {@code kafka.bootstrap.servers} alone: the workspace {@code export-quarkus.sh} exports
 * {@code KAFKA_BOOTSTRAP_SERVERS}, and environment variables outrank this resource's config
 * source, so a single shared key would silently send the relay to the ambient broker instead of
 * the container.
 */
public class MySQLTestResource implements QuarkusTestResourceLifecycleManager {

    private static final String[] NOTIFICATION_CHANNEL_NAMES = {
        "email-notifications", "sms-notifications", "whatsapp-notifications"
    };

    private MySQLContainer<?> mysql;
    private KafkaContainer kafka;

    /** Starts real MySQL and Kafka and enables the production outbox relay for resource tests. */
    @Override
    public Map<String, String> start() {
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));
        kafka.start();
        mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("hr_management_test")
                .withUsername("test")
                .withPassword("test")
                .withInitScript("schema.sql")
                // m03Staff/m07SystemParameters have 100+ legacy varchar columns; InnoDB's
                // strict-mode worst-case row-size check rejects the CREATE TABLE even though
                // DYNAMIC row format stores the actual data off-page fine — the live server this
                // schema came from evidently runs with strict mode off.
                .withCommand("--innodb-strict-mode=OFF");
        mysql.start();

        String reactiveUrl =
                String.format("mysql://%s:%d/%s", mysql.getHost(), mysql.getFirstMappedPort(), mysql.getDatabaseName());

        Map<String, String> configuration = new LinkedHashMap<>();
        configuration.put("quarkus.datasource.reactive.url", reactiveUrl);
        configuration.put("quarkus.datasource.username", mysql.getUsername());
        configuration.put("quarkus.datasource.password", mysql.getPassword());
        configuration.put("kafka.bootstrap.servers", kafka.getBootstrapServers());
        configuration.put("notification.outbox.enabled", "true");
        configuration.putAll(notificationChannelBootstrapServers(kafka.getBootstrapServers()));
        return configuration;
    }

    /** Pins every notification channel to the container broker for this test run. */
    private Map<String, String> notificationChannelBootstrapServers(String bootstrapServers) {
        Map<String, String> channelConfiguration = new LinkedHashMap<>();
        for (String channelName : NOTIFICATION_CHANNEL_NAMES) {
            channelConfiguration.put(channelBootstrapServersKey(channelName), bootstrapServers);
        }
        return channelConfiguration;
    }

    /** Builds the SmallRye channel attribute key that overrides the shared broker address. */
    private String channelBootstrapServersKey(String channelName) {
        return "mp.messaging.outgoing." + channelName + ".bootstrap.servers";
    }

    /** Stops both isolated infrastructure containers after the application tests finish. */
    @Override
    public void stop() {
        if (kafka != null) {
            kafka.stop();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }
}
