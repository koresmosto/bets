# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Start infrastructure (PostgreSQL, Kafka, Zookeeper, RocketMQ + UIs)
docker compose up -d

# Run application (requires Docker infra)
./mvnw spring-boot:run

# Run with H2 in-memory DB (no Docker needed)
./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=h2"

# Build
mvn clean install

# Run tests (uses Testcontainers, no external infra needed)
./mvnw test

# Run a single test
./mvnw test -Dtest=BettingIntegrationTest#shouldSettleBetsWhenEventOutcomeIsPublished
```

Test a live endpoint:
```bash
curl -X POST http://localhost:8080/api/event-outcomes \
  -H "Content-Type: application/json" \
  -d '{"eventId":"EVT-001","eventName":"Champions League Final","eventWinnerId":"TEAM-A"}'
```

## Architecture

Sports betting event settlement service. Receives event outcomes via REST, routes them through Kafka, queries PostgreSQL for matching bets, and sends settlement messages to RocketMQ.

**Data flow:**
```
POST /api/event-outcomes
  → EventOutcomeProducer → Kafka: "event-outcomes"
  → EventOutcomeConsumer
  → BetSettlementService → BetRepository (R2DBC/PostgreSQL)
  → BetSettlementProducer → RocketMQ: "bet-settlements"
```

**Stack:** Java 21, Spring Boot 3.5, Spring WebFlux (reactive), R2DBC, Spring Kafka, Apache RocketMQ 5.3.1, PostgreSQL 16.

## Key Design Decisions

- **Fully reactive** — all layers use `Mono`/`Flux`; the only exception is RocketMQ's synchronous send, which is offloaded to `Schedulers.boundedElastic()`
- **Kafka consumer** explicitly calls `.subscribe()` to trigger the reactive pipeline
- Integration tests use **Testcontainers** (PostgreSQL, Kafka, RocketMQ); no external infra required for tests
- H2 profile (`spring.profiles.active=h2`) enables local development without Docker; schema and sample data loaded from `schema.sql` / `data.sql` on startup
- Monitoring UIs when Docker infra is running: Kafka-UI on `:8090`, RocketMQ Dashboard on `:8091`

## Package Structure

All source under `com.phantom.betting`:
- `controller/` — REST endpoints
- `model/` — `EventOutcome` (record), `Bet` (entity), `BetSettlement` (record)
- `repository/` — `BetRepository` (ReactiveCrudRepository with custom query methods)
- `service/` — `BetSettlementService`
- `kafka/` — `EventOutcomeProducer`, `EventOutcomeConsumer`
- `rocketmq/` — `BetSettlementProducer`