package com.phantom.betting.kafka;

import com.phantom.betting.model.EventOutcome;
import com.phantom.betting.service.BetSettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventOutcomeConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventOutcomeConsumer.class);

    private final BetSettlementService betSettlementService;
    private final ObjectMapper objectMapper;

    public EventOutcomeConsumer(BetSettlementService betSettlementService, ObjectMapper objectMapper) {
        this.betSettlementService = betSettlementService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "event-outcomes", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(String message) {
        log.info("Received event outcome from Kafka: {}", message);
        try {
            EventOutcome outcome = objectMapper.readValue(message, EventOutcome.class);
            betSettlementService.processEventOutcome(outcome)
                    .doOnError(error -> log.error("Error processing event outcome: {}", outcome, error))
                    .subscribe();
        } catch (Exception e) {
            log.error("Failed to deserialize event outcome message: {}", message, e);
        }
    }
}
