package com.phantom.betting.repository;

import com.phantom.betting.model.Bet;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Repository
public interface BetRepository extends ReactiveCrudRepository<Bet, UUID> {

    Flux<Bet> findByEventId(String eventId);

    Flux<Bet> findByEventIdAndEventWinnerId(String eventId, String eventWinnerId);
}
