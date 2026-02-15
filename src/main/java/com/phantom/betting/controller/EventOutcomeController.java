package com.phantom.betting.controller;

import com.phantom.betting.kafka.EventOutcomeProducer;
import com.phantom.betting.model.EventOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/event-outcomes")
public class EventOutcomeController {

    private static final Logger log = LoggerFactory.getLogger(EventOutcomeController.class);

    private final EventOutcomeProducer eventOutcomeProducer;

    public EventOutcomeController(EventOutcomeProducer eventOutcomeProducer) {
        this.eventOutcomeProducer = eventOutcomeProducer;
    }

    @PostMapping
    public Mono<ResponseEntity<Map<String, String>>> publishEventOutcome(@RequestBody EventOutcome eventOutcome) {
        log.info("Received request to publish event outcome: eventId={}, eventName={}, winnerId={}",
                eventOutcome.eventId(), eventOutcome.eventName(), eventOutcome.eventWinnerId());

        return eventOutcomeProducer.send(eventOutcome)
                .then(Mono.just(ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(Map.of(
                                "status", "ACCEPTED",
                                "message", "Event outcome published to Kafka",
                                "eventId", eventOutcome.eventId()))))
                .onErrorResume(error -> {
                    log.error("Failed to publish event outcome", error);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of(
                                    "status", "ERROR",
                                    "message", "Failed to publish event outcome: " + error.getMessage())));
                });
    }
}
