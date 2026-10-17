ALTER TABLE deliveries
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3),
    ADD COLUMN next_attempt_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;

UPDATE deliveries SET attempt_count = 1, next_attempt_at = NULL WHERE status IN ('SUCCESS', 'FAILED');

CREATE TABLE delivery_attempts (
    delivery_id UUID NOT NULL REFERENCES deliveries (id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number BETWEEN 1 AND 3),
    http_status INTEGER CHECK (http_status BETWEEN 100 AND 599),
    error_message TEXT,
    finished_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (delivery_id, attempt_number),
    CHECK ((http_status BETWEEN 200 AND 299 AND http_status IS NOT NULL AND error_message IS NULL)
        OR error_message IS NOT NULL)
);

INSERT INTO delivery_attempts (delivery_id, attempt_number, http_status, error_message, finished_at)
SELECT id, 1, http_status, error_message, completed_at FROM deliveries WHERE status IN ('SUCCESS', 'FAILED');

DROP INDEX deliveries_pending_idx;
CREATE INDEX deliveries_pending_due_idx ON deliveries (next_attempt_at, created_at, id)
    WHERE status = 'PENDING';
