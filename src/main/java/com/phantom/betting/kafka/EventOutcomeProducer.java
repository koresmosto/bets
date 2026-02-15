package com.phantom.betting.kafka;

import com.phantom.betting.model.EventOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class EventOutcomeProducer {

    private static final Logger log = LoggerFactory.getLogger(EventOutcomeProducer.class);
    public static final String TOPIC = "event-outcomes";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public EventOutcomeProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> send(EventOutcome outcome) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(outcome))
                .flatMap(json -> Mono.fromFuture(
                        kafkaTemplate.send(TOPIC, outcome.eventId(), json).toCompletableFuture()))
                .doOnSuccess(result -> log.info("Published event outcome to Kafka topic '{}': eventId={}",
                        TOPIC, outcome.eventId()))
                .doOnError(error -> log.error("Failed to publish event outcome to Kafka: {}", outcome, error))
                .then();
    }
}
