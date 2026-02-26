# Improvement & Optimization Plan: Sports Betting Settlement Service

## Context

The service is a reactive Spring Boot 3.5 event settlement pipeline (REST → Kafka → R2DBC/PostgreSQL → RocketMQ). It is functionally complete but has several critical reactive anti-patterns that cause silent failures, missing idempotency protections (double-settlement on Kafka redelivery), a broken H2 dev profile, no input validation, no observability, and weak test coverage. This document covers all modules in priority order.

---

## TIER 1 — Critical Fixes (Active Bugs / Silent Failures)

### 1.1 Fix `doOnNext` anti-pattern in BetSettlementService

**File:** `src/main/java/com/phantom/betting/service/BetSettlementService.java`

**Problem:** `doOnNext(betSettlementProducer::send)` is a side-effect operator — its return value and thrown exceptions are not handled by the reactive chain. A RocketMQ failure silently terminates the `Flux` mid-stream, dropping unsettled bets.

**Fix:** Replace with `flatMap` wrapping the synchronous call in `Mono.fromCallable()`:

```java
.flatMap(settlement ->
    Mono.fromCallable(() -> { betSettlementProducer.send(settlement); return settlement; })
        .subscribeOn(Schedulers.boundedElastic())
        .doOnError(err -> log.error("Failed to send settlement: betId={}", settlement.betId(), err))
        .onErrorResume(err -> Mono.empty())
)
```

Each `BetSettlement` failure is now caught and logged independently; the rest of the stream continues.

---

### 1.2 Fix bare `.subscribe()` in EventOutcomeConsumer

**File:** `src/main/java/com/phantom/betting/kafka/EventOutcomeConsumer.java`

**Problem:** `.subscribe()` with no error consumer routes errors to `Hooks.onErrorDropped`, silently discarding them.

**Fix:**
```java
betSettlementService.processEventOutcome(outcome)
    .subscribe(
        null,
        error -> log.error("Error processing eventId={}: {}", outcome.eventId(), error.getMessage(), error),
        () -> log.debug("Processing complete for eventId={}", outcome.eventId())
    );
```

---

### 1.3 Fix broken H2 profile configuration

**File:** `src/main/resources/application-h2.yml`

**Problem:** The file configures JDBC/JPA (`spring.datasource`, `spring.jpa`), which do nothing in an R2DBC project. The H2 profile as documented (`--spring.profiles.active=h2`) does not actually work.

**Fix:**
```yaml
spring:
  r2dbc:
    url: r2dbc:h2:mem:///betting_db;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    username: sa
    password: ""
  sql:
    init:
      mode: always
  h2:
    console:
      enabled: true
```

Also add to `pom.xml`:
```xml
<dependency>
    <groupId>io.r2dbc</groupId>
    <artifactId>r2dbc-h2</artifactId>
    <optional>true</optional>
</dependency>
```

---

### 1.4 Remove internal error message leakage from controller

**File:** `src/main/java/com/phantom/betting/controller/EventOutcomeController.java`

**Problem:** `"Failed to publish event outcome: " + error.getMessage()` can expose connection strings, class names, or infrastructure topology to API callers.

**Fix:** Return a generic message; log the full error server-side:
```java
log.error("Failed to publish event outcome for eventId={}", eventOutcome.eventId(), error);
return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
    .body(Map.of(
        "status", "ERROR",
        "message", "Failed to publish event outcome. Please try again later.",
        "eventId", eventOutcome.eventId())));
```

---

## TIER 2 — Architecture (Idempotency, DLT, Reliable Delivery)

### 2.1 Add bet idempotency via status column + optimistic CAS

**Files:** `schema.sql`, `model/Bet.java`, `repository/BetRepository.java`, `service/BetSettlementService.java`

**Problem:** Kafka's at-least-once delivery means the same `event-outcomes` message can be redelivered. Without a status guard, the same bets get settled twice.

**Schema changes (`schema.sql`):**
```sql
ALTER TABLE bets
    ADD COLUMN status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    ADD COLUMN settled_at      TIMESTAMPTZ,
    ADD COLUMN version         INTEGER      NOT NULL DEFAULT 0;
```

**`Bet.java` additions:**
```java
@Column("status")
private BetStatus status = BetStatus.PENDING;

@Column("settled_at")
private Instant settledAt;

@Version
private Integer version;

public enum BetStatus { PENDING, SETTLED, FAILED }
```

**New repository method (`BetRepository.java`):**
```java
@Query("UPDATE bets SET status = 'SETTLED', settled_at = NOW(), version = version + 1 " +
       "WHERE id = :id AND status = 'PENDING' RETURNING id")
Mono<UUID> markAsSettled(UUID id);

@Query("SELECT * FROM bets WHERE event_id = :eventId AND event_winner_id = :eventWinnerId AND status = 'PENDING'")
Flux<Bet> findPendingByEventIdAndEventWinnerId(String eventId, String eventWinnerId);
```

**Service change:** Query only `PENDING` bets; call `markAsSettled()` atomically before sending. The UPDATE WHERE status='PENDING' is a compare-and-swap — concurrent redeliveries only settle each bet once.

---

### 2.2 Add Dead Letter Topic (DLT) for failed Kafka messages

**Files:** New `kafka/KafkaConfig.java`, `application.yml`

**Problem:** Deserialization failures and unrecoverable processing errors silently drop messages.

**`KafkaConfig.java`:**
```java
@Bean
public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
        (record, ex) -> new TopicPartition("event-outcomes.DLT", record.partition()));
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    handler.addNotRetryableExceptions(JsonProcessingException.class);
    return handler;
}
```

Transient errors get 3 retries with 1s backoff. Permanent errors (bad JSON) go straight to DLT.

---

### 2.3 Add retry logic to RocketMQ producer

**File:** `rocketmq/BetSettlementProducer.java`

Convert `send()` to return `Mono<Void>` with `Retry.backoff(3, Duration.ofMillis(500))`. Non-retryable: `JsonProcessingException`. Integrates cleanly with the `flatMap` fix from 1.1.

---

### 2.4 Enable Kafka producer idempotence

**File:** `application.yml`

```yaml
spring:
  kafka:
    producer:
      retries: 3
      properties:
        acks: all
        enable.idempotence: true
        max.in.flight.requests.per.connection: 1
        delivery.timeout.ms: 30000
```

Guarantees exactly-once producer semantics at the Kafka client level.

---

## TIER 3 — Code Quality (Validation, Typed Responses, Models)

### 3.1 Add input validation

**Files:** `model/EventOutcome.java`, `controller/EventOutcomeController.java`, `pom.xml`

Add `spring-boot-starter-validation`. Annotate `EventOutcome` fields with `@NotBlank`. Add `@Valid` to controller `@RequestBody`. Rejects malformed requests at the boundary before any Kafka or DB interaction.

### 3.2 Replace `Map<String, String>` response with typed DTO

**New file:** `controller/dto/EventOutcomeResponse.java`

```java
public record EventOutcomeResponse(String status, String message, String eventId) {
    public static EventOutcomeResponse accepted(String eventId) { ... }
    public static EventOutcomeResponse error(String eventId) { ... }
}
```

### 3.3 Add global exception handler

**New file:** `controller/GlobalExceptionHandler.java` (`@RestControllerAdvice`)

Handles `WebExchangeBindException` → 400 with field-level errors; generic `Exception` → 500 with safe message. Single, consistent error response format across all endpoints.

### 3.4 Add audit fields and `@Version` to Bet entity

**Files:** `model/Bet.java`, `schema.sql`, `BettingApplication.java`

Add `@CreatedDate Instant createdAt`, `@LastModifiedDate Instant updatedAt`. Enable `@EnableR2dbcAuditing` on the main application class.

---

## TIER 4 — Observability (Metrics, Logging, Health)

### 4.1 Add Micrometer/Prometheus metrics

**Files:** `pom.xml`, `BetSettlementService.java`, `application.yml`

Dependencies: `spring-boot-starter-actuator`, `micrometer-registry-prometheus`.

Metrics to add:
- `bets.settlements.processed` (Counter)
- `bets.settlements.failed` (Counter)
- `bets.settlement.latency` (Timer)

Expose `/actuator/prometheus` and `/actuator/health`.

### 4.2 Add correlation ID filter

**New file:** `filter/CorrelationIdFilter.java`

Reads/generates `X-Correlation-ID` header, writes to Reactor context and response header. Thread through Kafka message headers and MDC pattern for end-to-end log tracing without a full APM setup.

### 4.3 Add RocketMQ health indicator

**New file:** `health/RocketMqHealthIndicator.java`

Implements `ReactiveHealthIndicator`, checks `rocketMQTemplate.getProducer().isRunning()`. Makes `/actuator/health` reflect actual RocketMQ connectivity for Kubernetes readiness probes.

---

## TIER 5 — Configuration (Production Readiness)

### 5.1 Externalize credentials to environment variables

**File:** `application.yml`

```yaml
spring:
  r2dbc:
    url: ${DB_URL:r2dbc:postgresql://localhost:5432/betting_db}
    username: ${DB_USERNAME:betting_user}
    password: ${DB_PASSWORD:betting_pass}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
rocketmq:
  name-server: ${ROCKETMQ_NAME_SERVER:localhost:9876}
```

Defaults allow local development; production uses injected env vars or secrets manager.

### 5.2 Configure R2DBC connection pool

**File:** `application.yml`

```yaml
spring:
  r2dbc:
    pool:
      initial-size: 5
      max-size: 20
      max-idle-time: 30m
      validation-query: SELECT 1
      acquire-retry: 3
```

### 5.3 Configure Kafka manual commit + listener concurrency

**File:** `application.yml`

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false
      max-poll-records: 10
    listener:
      ack-mode: MANUAL_IMMEDIATE
      concurrency: 3
```

Offsets committed only after processing starts. `concurrency: 3` enables parallel partition consumption.

### 5.4 Add graceful shutdown

**File:** `application.yml`

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

In-flight reactive chains complete before JVM terminates; prevents data loss on pod restart.

### 5.5 Fix Docker Compose health checks

**File:** `docker-compose.yml`

Add health checks for Kafka and RocketMQ namesrv. Set `rocketmq-broker.depends_on.rocketmq-namesrv.condition: service_healthy`. Prevents broker registration race conditions on startup.

---

## TIER 6 — Testing (Unit Tests, Error Scenarios, E2E)

### 6.1 Add unit tests for BetSettlementService

**New file:** `src/test/java/com/phantom/betting/service/BetSettlementServiceTest.java`

Tests (Mockito + StepVerifier, no containers):
- Happy path: matching bets are settled and sent
- One bet's send fails → pipeline continues for remaining bets
- No bets found → completes without calling producer

### 6.2 Add unit tests for EventOutcomeConsumer

**New file:** `src/test/java/com/phantom/betting/kafka/EventOutcomeConsumerTest.java`

Tests: valid JSON deserialization calls service; malformed JSON logs error and does not call service.

### 6.3 Strengthen RocketMQ end-to-end integration test

**File:** `BettingIntegrationTest.java`

Add a `DefaultLitePullConsumer` in the test to verify `BetSettlement` messages are actually published to RocketMQ (currently untested — the test only checks the DB state, not the downstream output).

### 6.4 Add error scenario tests

**File:** `BettingIntegrationTest.java`

Add tests for: blank `eventId` → HTTP 400, malformed JSON body → HTTP 400.

---

## TIER 7 — Performance & Database

### 7.1 Replace single-column index with compound index

**File:** `schema.sql`

```sql
DROP INDEX IF EXISTS idx_bets_event_id;
CREATE INDEX IF NOT EXISTS idx_bets_event_winner_status
    ON bets(event_id, event_winner_id, status);
```

The settlement query filters on all three columns; this index allows PostgreSQL to satisfy it without a table scan even at millions of rows.

### 7.2 Add explicit `@Query` annotations to BetRepository

**File:** `repository/BetRepository.java`

Replace all method-name-parsed queries with explicit SQL. Predictable query generation, directly debuggable with `EXPLAIN ANALYZE`.

### 7.3 Add pagination for large event result sets

**Files:** `service/BetSettlementService.java`, `repository/BetRepository.java`

Process bets in pages of 500 using recursive `Mono` chaining. Prevents OOM when a single event has tens of thousands of bets loaded into JVM memory as an unbounded `Flux`.

```java
private Mono<Void> processPage(EventOutcome outcome, int offset) {
    return betRepository.findPendingPaged(outcome.eventId(), outcome.eventWinnerId(), 500, offset)
        .flatMap(bet -> settleAndSend(bet, outcome), 10)
        .count()
        .flatMap(count -> count == 500 ? processPage(outcome, offset + 500) : Mono.empty());
}
```

### 7.4 Add OpenAPI documentation

**Files:** `pom.xml` (add `springdoc-openapi-starter-webflux-ui 2.6.0`), `EventOutcomeController.java`

Swagger UI available at `/swagger-ui.html`.

---

## Files Affected Summary

| File | Tiers |
|------|-------|
| `service/BetSettlementService.java` | 1.1, 2.1, 4.1, 7.3 |
| `kafka/EventOutcomeConsumer.java` | 1.2, 2.2 |
| `rocketmq/BetSettlementProducer.java` | 2.3 |
| `model/Bet.java` | 2.1, 3.4 |
| `repository/BetRepository.java` | 2.1, 7.2 |
| `schema.sql` | 2.1, 3.4, 7.1 |
| `application.yml` | 2.4, 4.1, 5.1–5.4 |
| `application-h2.yml` | 1.3 |
| `controller/EventOutcomeController.java` | 1.4, 3.1, 3.2 |
| `docker-compose.yml` | 5.5 |
| `pom.xml` | 1.3, 3.1, 4.1, 7.4 |
| New: `kafka/KafkaConfig.java` | 2.2 |
| New: `controller/GlobalExceptionHandler.java` | 3.3 |
| New: `controller/dto/EventOutcomeResponse.java` | 3.2 |
| New: `filter/CorrelationIdFilter.java` | 4.2 |
| New: `health/RocketMqHealthIndicator.java` | 4.3 |
| New: `exception/BetSettlementException.java` | 2.3 |
| New: `BetSettlementServiceTest.java` | 6.1 |
| New: `EventOutcomeConsumerTest.java` | 6.2 |

---

## Verification Checklist

1. **H2 profile:** `./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=h2"` — app starts, H2 console at `/h2-console`
2. **Validation:** `curl -X POST .../api/event-outcomes -d '{"eventId":""}' -H 'Content-Type: application/json'` → HTTP 400 with field errors
3. **Full pipeline:** POST valid event outcome → 202 returned; verify bets have `status=SETTLED` in DB; verify RocketMQ dashboard shows messages on `bet-settlements` topic
4. **Idempotency:** POST same event outcome twice → only one settlement per bet; second run hits no PENDING bets
5. **Metrics:** `curl http://localhost:8080/actuator/prometheus | grep bets_`
6. **Tests:** `./mvnw test` — all tests pass including new unit tests
7. **Graceful shutdown:** Send SIGTERM during active processing; verify in-flight requests complete