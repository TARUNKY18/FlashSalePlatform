CREATE DATABASE IF NOT EXISTS flash_sale;

CREATE TABLE IF NOT EXISTS flash_sale.sale_events
(
    event_id         String,
    event_type       LowCardinality(String),
    event_version    LowCardinality(String),
    sale_id          String,
    user_id          String,
    occurred_at      DateTime64(3, 'UTC'),
    ingested_at      DateTime64(3, 'UTC') DEFAULT now64(3),
    raw_payload      String,
    INDEX idx_sale_id sale_id TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX idx_event_type event_type TYPE set(10) GRANULARITY 4
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(occurred_at)
ORDER BY (sale_id, occurred_at, event_id)
TTL occurred_at + INTERVAL 90 DAY
SETTINGS index_granularity = 8192;