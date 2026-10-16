CREATE TABLE deliveries (
    id UUID PRIMARY KEY,
    event TEXT NOT NULL,
    payload JSONB NOT NULL,
    target_url TEXT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED')),
    http_status INTEGER CHECK (http_status BETWEEN 100 AND 599),
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CHECK (
        (status = 'PENDING' AND completed_at IS NULL AND http_status IS NULL AND error_message IS NULL)
        OR (status = 'SUCCESS' AND completed_at IS NOT NULL AND http_status IS NOT NULL
            AND http_status BETWEEN 200 AND 299 AND error_message IS NULL)
        OR (status = 'FAILED' AND completed_at IS NOT NULL AND error_message IS NOT NULL)
    )
);
