package com.phantom.betting.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.UUID;

public record BetSettlement(
        @JsonProperty("betId") UUID betId,
        @JsonProperty("userId") String userId,
        @JsonProperty("eventId") String eventId,
        @JsonProperty("eventMarketId") String eventMarketId,
        @JsonProperty("eventWinnerId") String eventWinnerId,
        @JsonProperty("betAmount") BigDecimal betAmount,
        @JsonProperty("eventName") String eventName) {
}
