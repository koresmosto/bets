package com.phantom.betting.service;

import com.phantom.betting.model.BetSettlement;
import com.phantom.betting.model.EventOutcome;
import com.phantom.betting.repository.BetRepository;
import com.phantom.betting.rocketmq.BetSettlementProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class BetSettlementService {

    private static final Logger log = LoggerFactory.getLogger(BetSettlementService.class);

    private final BetRepository betRepository;
    private final BetSettlementProducer betSettlementProducer;

    public BetSettlementService(BetRepository betRepository, BetSettlementProducer betSettlementProducer) {
        this.betRepository = betRepository;
        this.betSettlementProducer = betSettlementProducer;
    }

    /**
     * Processes an event outcome by:
     * 1. Querying the database for bets matching the event ID
     * 2. Mapping each matched bet to a BetSettlement
     * 3. Sending each settlement to RocketMQ
     */
    public Mono<Void> processEventOutcome(EventOutcome outcome) {
        log.info("Processing event outcome: eventId={}, eventName={}, winnerId={}",
                outcome.eventId(), outcome.eventName(), outcome.eventWinnerId());

        return betRepository.findByEventIdAndEventWinnerId(outcome.eventId(), outcome.eventWinnerId())
                .doOnNext(bet -> log.info("Found bet to settle: {}", bet))
                .map(bet -> new BetSettlement(
                        bet.getId(),
                        bet.getUserId(),
                        bet.getEventId(),
                        bet.getEventMarketId(),
                        bet.getEventWinnerId(),
                        bet.getBetAmount(),
                        outcome.eventName()))
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(betSettlementProducer::send)
                .doOnComplete(() -> log.info("Finished processing event outcome for eventId={}",
                        outcome.eventId()))
                .then();
    }
}
