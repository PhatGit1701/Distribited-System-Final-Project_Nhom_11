-- Persistent moderation is scoped by account and peer ID, preventing cross-account poisoning.
CREATE TABLE peer_reputation (
    user_id BIGINT NOT NULL REFERENCES users(id),
    peer_id VARCHAR(128) NOT NULL CHECK (peer_id ~ '^[a-zA-Z0-9_-]{1,128}$'),
    is_banned BOOLEAN NOT NULL DEFAULT FALSE,
    bad_chunk_count BIGINT NOT NULL DEFAULT 0 CHECK (bad_chunk_count >= 0),
    PRIMARY KEY (user_id, peer_id)
);
