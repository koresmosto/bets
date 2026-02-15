-- Sample bets for local testing
INSERT INTO bets (id, user_id, event_id, event_market_id, event_winner_id, bet_amount) VALUES
    ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'USER-001', 'EVT-001', 'MKT-MATCH-WINNER', 'TEAM-A', 50.00),
    ('b2c3d4e5-f6a7-8901-bcde-f12345678901', 'USER-002', 'EVT-001', 'MKT-MATCH-WINNER', 'TEAM-B', 100.00),
    ('c3d4e5f6-a7b8-9012-cdef-123456789012', 'USER-003', 'EVT-001', 'MKT-FIRST-GOAL',   'TEAM-A', 25.00),
    ('d4e5f6a7-b8c9-0123-defa-234567890123', 'USER-001', 'EVT-002', 'MKT-MATCH-WINNER', 'TEAM-C', 75.00),
    ('e5f6a7b8-c9d0-1234-efab-345678901234', 'USER-004', 'EVT-002', 'MKT-OVER-UNDER',   'TEAM-D', 200.00),
    ('f6a7b8c9-d0e1-2345-fabc-456789012345', 'USER-005', 'EVT-003', 'MKT-MATCH-WINNER', 'TEAM-E', 150.00)
ON CONFLICT (id) DO NOTHING;
