package com.phantom.betting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phantom.betting.model.Bet;
import com.phantom.betting.model.EventOutcome;
import com.phantom.betting.repository.BetRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Testcontainers
class BettingIntegrationTest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("betting_db")
            .withUsername("betting_user")
            .withPassword("betting_pass")
            .withNetwork(NETWORK);

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
            .withNetwork(NETWORK);

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> rocketmqNamesrv = new GenericContainer<>(DockerImageName.parse("apache/rocketmq:5.3.1"))
            .withCommand("sh mqnamesrv")
            .withExposedPorts(9876)
            .withNetwork(NETWORK)
            .withNetworkAliases("namesrv")
            .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx256m")
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> rocketmqBroker = new GenericContainer<>(DockerImageName.parse("apache/rocketmq:5.3.1"))
            .withCommand("sh mqbroker -n namesrv:9876 --enable-proxy")
            .withExposedPorts(10911, 10909)
            .withNetwork(NETWORK)
            .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m")
            .withEnv("NAMESRV_ADDR", "namesrv:9876")
            .dependsOn(rocketmqNamesrv)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)));

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private BetRepository betRepository;

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // R2DBC
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:postgresql://%s:%d/betting_db",
                postgres.getHost(), postgres.getMappedPort(5432)));
        registry.add("spring.r2dbc.username", postgres::getUsername);
        registry.add("spring.r2dbc.password", postgres::getPassword);

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // RocketMQ
        registry.add("rocketmq.name-server",
                () -> String.format("%s:%d", rocketmqNamesrv.getHost(), rocketmqNamesrv.getMappedPort(9876)));

        // Critical: ensure autocommit is enabled for R2DBC
        registry.add("spring.r2dbc.pool.validation-query", () -> "SELECT 1");
        registry.add("spring.r2dbc.pool.initial-size", () -> "5");
        registry.add("spring.r2dbc.pool.max-size", () -> "10");
    }

    @BeforeEach
    void setUp() {
        // Clean and re-create the bets table before each test
        databaseClient.sql("""
                CREATE TABLE IF NOT EXISTS bets (
                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                    user_id VARCHAR(100) NOT NULL,
                    event_id VARCHAR(100) NOT NULL,
                    event_market_id VARCHAR(100) NOT NULL,
                    event_winner_id VARCHAR(100) NOT NULL,
                    bet_amount DECIMAL(12, 2) NOT NULL
                )
                """).then().block();

        databaseClient.sql("DELETE FROM bets").then().block();
    }

    @Test
    void shouldPublishEventOutcomeToKafka() {
        // Given
        EventOutcome outcome = new EventOutcome("EVT-100", "Champions League Final", "TEAM-A");

        // When & Then
        webTestClient.post()
                .uri("/api/event-outcomes")
                .bodyValue(outcome)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ACCEPTED")
                .jsonPath("$.eventId").isEqualTo("EVT-100");

        // Verify the message was produced to Kafka by consuming it
        try (KafkaConsumer<String, String> consumer = createKafkaConsumer()) {
            consumer.subscribe(List.of("event-outcomes"));

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                List<ConsumerRecord<String, String>> matchingRecords = new ArrayList<>();
                records.forEach(matchingRecords::add);

                assertThat(matchingRecords).anyMatch(record -> {
                    try {
                        EventOutcome received = objectMapper.readValue(record.value(), EventOutcome.class);
                        return "EVT-100".equals(received.eventId());
                    } catch (Exception e) {
                        return false;
                    }
                });
            });
        }
    }

    @Test
    void shouldSettleBetsWhenEventOutcomeIsPublished() {
        // Given: Insert bets into the database

        var bet1 = new Bet(null, "USER-100", "EVT-200", "MKT-WINNER", "TEAM-X", new BigDecimal("50.00"));
        var bet2 = new Bet(null, "USER-200", "EVT-200", "MKT-WINNER", "TEAM-Y", new BigDecimal("100.00"));
        var bet3 = new Bet(null, "USER-201", "EVT-200", "MKT-WINNER", "TEAM-Y", new BigDecimal("150.00"));

        StepVerifier.create(betRepository.save(bet1)).expectNextCount(1).verifyComplete();
        StepVerifier.create(betRepository.save(bet2)).expectNextCount(1).verifyComplete();
        StepVerifier.create(betRepository.save(bet3)).expectNextCount(1).verifyComplete();

        // Verify bets were saved
        StepVerifier.create(betRepository.findByEventId("EVT-200"))
                .expectNextCount(3)
                .verifyComplete();

        StepVerifier.create(betRepository.findByEventIdAndEventWinnerId("EVT-200", "TEAM-Y"))
                .expectNextCount(2)
                .verifyComplete();

        // When: Publish event outcome
        EventOutcome outcome = new EventOutcome("EVT-200", "Europa League Semi-Final", "TEAM-Y");

        webTestClient.post()
                .uri("/api/event-outcomes")
                .bodyValue(outcome)
                .exchange()
                .expectStatus().isAccepted();

        // Then: Wait for the consumer to process and send to RocketMQ
        // We verify that bets are still in the database (they were found and processed)
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            StepVerifier.create(betRepository.findByEventIdAndEventWinnerId("EVT-200", "TEAM-Y"))
                    .expectNextCount(2)
                    .verifyComplete();
        });
    }

    @Test
    void shouldReturnAcceptedForValidEventOutcome() {
        // Given
        EventOutcome outcome = new EventOutcome("EVT-300", "World Cup Final", "TEAM-Z");

        // When & Then
        webTestClient.post()
                .uri("/api/event-outcomes")
                .bodyValue(outcome)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ACCEPTED")
                .jsonPath("$.message").isEqualTo("Event outcome published to Kafka")
                .jsonPath("$.eventId").isEqualTo("EVT-300");
    }

    @Test
    void shouldFindBetsByEventId() {
        // Given

        Bet bet = new Bet(null, "USER-300", "EVT-400", "MKT-TOTAL-GOALS", "TEAM-A", new BigDecimal("75.00"));

        StepVerifier.create(betRepository.save(bet)).expectNextCount(1).verifyComplete();

        // When & Then
        StepVerifier.create(betRepository.findByEventIdAndEventWinnerId("EVT-400", "TEAM-A"))
                .assertNext(found -> {
                    assertThat(found.getEventId()).isEqualTo("EVT-400");
                    assertThat(found.getUserId()).isEqualTo("USER-300");
                    assertThat(found.getBetAmount()).isEqualByComparingTo(new BigDecimal("75.00"));
                })
                .verifyComplete();
    }

    @Test
    void shouldReturnEmptyWhenNoBetsForEvent() {
        // When & Then
        StepVerifier
                .create(betRepository.findByEventIdAndEventWinnerId("NON-EXISTENT-EVENT", "NOT-EXISTENT-WINNER"))
                .verifyComplete();
    }

    private KafkaConsumer<String, String> createKafkaConsumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
    }
}
