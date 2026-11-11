ALTER TABLE user_file_stats ADD COLUMN started_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE user_file_stats ADD COLUMN stopped_at TIMESTAMP WITH TIME ZONE;

-- The receipt and its increment commit together. Retrying an ambiguously committed
-- batch is safe. Retain receipts while any tracker can still retry its batch.
CREATE TABLE stats_flush_receipts (
    batch_id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
