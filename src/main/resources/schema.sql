CREATE TABLE IF NOT EXISTS bets (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     VARCHAR(100)   NOT NULL,
    event_id    VARCHAR(100)   NOT NULL,
    event_market_id VARCHAR(100) NOT NULL,
    event_winner_id VARCHAR(100) NOT NULL,
    bet_amount  DECIMAL(12, 2) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bets_event_id ON bets(event_id);
