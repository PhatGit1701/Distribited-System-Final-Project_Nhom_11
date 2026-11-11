ALTER TABLE peer_reputation ADD COLUMN ban_until TIMESTAMP WITH TIME ZONE;
ALTER TABLE peer_reputation ADD COLUMN suspicion_until TIMESTAMP WITH TIME ZONE;

-- Legacy free-text reports retain NULL evidence and never enter the quarantine quorum.
ALTER TABLE peer_reports ADD COLUMN target_user_id BIGINT REFERENCES users(id);
ALTER TABLE peer_reports ADD COLUMN chunk_index INTEGER CHECK (chunk_index >= 0);
ALTER TABLE peer_reports ADD COLUMN expected_hash VARCHAR(64) CHECK (expected_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE peer_reports ADD COLUMN actual_hash VARCHAR(64) CHECK (actual_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE peer_reports ADD CONSTRAINT peer_reports_evidence CHECK (
    (target_user_id IS NULL AND chunk_index IS NULL AND expected_hash IS NULL AND actual_hash IS NULL)
    OR (target_user_id IS NOT NULL AND chunk_index IS NOT NULL AND expected_hash IS NOT NULL
        AND actual_hash IS NOT NULL AND expected_hash <> actual_hash AND reporter_id <> target_user_id)
);
CREATE INDEX peer_reports_quorum_idx ON peer_reports(target_user_id,peer_id,created_at)
    WHERE target_user_id IS NOT NULL;
