package com.phantom.betting.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Table("bets")
public class Bet {

    @Id
    private UUID id;

    @Column("user_id")
    private String userId;

    @Column("event_id")
    private String eventId;

    @Column("event_market_id")
    private String eventMarketId;

    @Column("event_winner_id")
    private String eventWinnerId;

    @Column("bet_amount")
    private BigDecimal betAmount;

    public Bet() {
    }

    public Bet(UUID id, String userId, String eventId, String eventMarketId,
            String eventWinnerId, BigDecimal betAmount) {
        this.id = id;
        this.userId = userId;
        this.eventId = eventId;
        this.eventMarketId = eventMarketId;
        this.eventWinnerId = eventWinnerId;
        this.betAmount = betAmount;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventMarketId() {
        return eventMarketId;
    }

    public void setEventMarketId(String eventMarketId) {
        this.eventMarketId = eventMarketId;
    }

    public String getEventWinnerId() {
        return eventWinnerId;
    }

    public void setEventWinnerId(String eventWinnerId) {
        this.eventWinnerId = eventWinnerId;
    }

    public BigDecimal getBetAmount() {
        return betAmount;
    }

    public void setBetAmount(BigDecimal betAmount) {
        this.betAmount = betAmount;
    }

    @Override
    public String toString() {
        return "Bet{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", eventId='" + eventId + '\'' +
                ", eventMarketId='" + eventMarketId + '\'' +
                ", eventWinnerId='" + eventWinnerId + '\'' +
                ", betAmount=" + betAmount +
                '}';
    }
}
