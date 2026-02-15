package com.phantom.betting.rocketmq;

import com.phantom.betting.model.BetSettlement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class BetSettlementProducer {

    private static final Logger log = LoggerFactory.getLogger(BetSettlementProducer.class);
    private static final String TOPIC = "bet-settlements";

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    public BetSettlementProducer(RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
    }

    public void send(BetSettlement settlement) {
        try {
            String json = objectMapper.writeValueAsString(settlement);
            rocketMQTemplate.send(TOPIC, MessageBuilder.withPayload(json).build());
            log.info("Sent bet settlement to RocketMQ topic '{}': betId={}, eventId={}",
                    TOPIC, settlement.betId(), settlement.eventId());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize BetSettlement: {}", settlement, e);
            throw new RuntimeException("Failed to serialize BetSettlement", e);
        }
    }
}
