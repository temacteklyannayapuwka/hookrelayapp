CREATE INDEX deliveries_pending_idx ON deliveries (created_at, id) WHERE status = 'PENDING';
