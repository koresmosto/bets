package com.phantom.betting.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EventOutcome(
        @JsonProperty("eventId") String eventId,
        @JsonProperty("eventName") String eventName,
        @JsonProperty("eventWinnerId") String eventWinnerId) {
}
