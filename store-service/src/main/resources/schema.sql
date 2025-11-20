-- Partial index for Outbox table to improve performance of polling
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (created_at) WHERE processed = false;
