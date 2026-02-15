# Sports Betting Event Settlement Application

A reactive Spring Boot 3.5 application that simulates sports betting event outcome handling and bet settlement via **Kafka** and **Apache RocketMQ**.

## Architecture

```
┌──────────────┐     ┌──────────────┐     ┌────────────────┐     ┌──────────────┐
│  REST API    │────▶│    Kafka      │────▶│  Kafka         │────▶│ PostgreSQL   │
│  POST        │     │  (event-     │     │  Consumer      │     │ (bets table) │
│  /api/event- │     │   outcomes)  │     │                │     │              │
│   outcomes   │     └──────────────┘     └───────┬────────┘     └──────────────┘
└──────────────┘                                  │
                                                  ▼
                                          ┌───────────────┐     ┌──────────────┐
                                          │ Bet Settlement │────▶│  RocketMQ    │
                                          │ Service        │     │  (bet-       │
                                          │                │     │  settlements)│
                                          └───────────────┘     └──────────────┘
```

### Flow

1. **API Endpoint** → Receives an event outcome (Event ID, Event Name, Event Winner ID) via `POST /api/event-outcomes`
2. **Kafka Producer** → Publishes the event outcome to the `event-outcomes` Kafka topic
3. **Kafka Consumer** → Listens to `event-outcomes` and triggers bet settlement
4. **Bet Settlement Service** → Queries PostgreSQL for bets matching the Event ID
5. **RocketMQ Producer** → Sends settlement messages to the `bet-settlements` RocketMQ topic

## Prerequisites

- **Java 21** (JDK)
- **Docker & Docker Compose** (for infrastructure services)
- **Maven 3.9+** (or use the included Maven wrapper `./mvnw`)

## Technology Stack

| Technology | Purpose |
|---|---|
| Spring Boot 3.5.7 | Application framework |
| Spring WebFlux | Reactive REST API |
| Project Reactor | Reactive programming model |
| Spring Data R2DBC | Reactive database access |
| PostgreSQL 16 | Bet storage |
| Spring Kafka | Kafka producer & consumer |
| Apache RocketMQ | Bet settlement messaging |
| Testcontainers | Integration test infrastructure |
| JUnit 5 | Testing framework |


## Quick Start

### 1. Build and Start Infrastructure Services

```bash
mvn clean install  

docker compose up -d
```

This starts:
- **PostgreSQL 16** on port `5432`  
  (h2 embedded db can be used instead by running "h2" spring profile)
- **Kafka** (KRaft mode) on port `9092`
- **RocketMQ NameServer** on port `9876`
- **RocketMQ Broker** on port `10911`

Wait for all services to be healthy:

```bash
docker compose ps
```

### 2. Run the Application

```bash
./mvnw spring-boot:run
```

The application starts on **http://localhost:8080** and automatically:
- Creates the `bets` table (via `schema.sql`)
- Inserts sample bet data (via `data.sql`)

### 3. Publish an Event Outcome

```bash
curl -X POST http://localhost:8080/api/event-outcomes \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "EVT-001",
    "eventName": "Champions League Final",
    "eventWinnerId": "TEAM-A"
  }'
```

**Expected response** (HTTP 202 Accepted):
```json
{
  "status": "ACCEPTED",
  "message": "Event outcome published to Kafka",
  "eventId": "EVT-001"
}
```

The application will:
1. Publish the event outcome to the `event-outcomes` Kafka topic
2. The Kafka consumer picks it up and queries PostgreSQL for bets with `event_id = 'EVT-001'`
3. Found bets (3 in sample data) are sent as settlement messages to RocketMQ's `bet-settlements` topic

Watch the application logs to see the full flow:

```bash
# In the terminal running the app, you'll see:
# Published event outcome to Kafka topic 'event-outcomes': eventId=EVT-001
# Received event outcome from Kafka: {...}
# Processing event outcome: eventId=EVT-001 ...
# Found bet to settle: Bet{...}
# Sent bet settlement to RocketMQ topic 'bet-settlements': betId=...
```

#### Sample Data

The application ships with sample bets:

| Bet ID | User | Event | Market | Winner Pick | Amount |
|--------|------|-------|--------|-------------|--------|
| a1b2c3... | USER-001 | EVT-001 | MKT-MATCH-WINNER | TEAM-A | $50.00 |
| b2c3d4... | USER-002 | EVT-001 | MKT-MATCH-WINNER | TEAM-B | $100.00 |
| c3d4e5... | USER-003 | EVT-001 | MKT-FIRST-GOAL | TEAM-A | $25.00 |
| d4e5f6... | USER-001 | EVT-002 | MKT-MATCH-WINNER | TEAM-C | $75.00 |
| e5f6a7... | USER-004 | EVT-002 | MKT-OVER-UNDER | TEAM-D | $200.00 |
| f6a7b8... | USER-005 | EVT-003 | MKT-MATCH-WINNER | TEAM-E | $150.00 |

Try different event IDs: `EVT-001` (3 bets), `EVT-002` (2 bets), `EVT-003` (1 bet).

### 4 Stop application and Infra

```bash
# Stop the Spring Boot app: Ctrl+C

# Stop infrastructure
docker compose down

# Stop and remove volumes
docker compose down -v
```

## UI tools (monitoring, handling)

- Embedded IntelliJ Idea tools for DB:   
  endpoint: localhost:5432  
  creds: betting_db/betting_user/betting_pass

- Kafka-UI   
  endpoint: localhost:8090

- RocketMQ-UI (RocketMQ Dashboard)   
  endpoint: localhost:8091

> **Note:** Docker env. infrastructure mast be run
