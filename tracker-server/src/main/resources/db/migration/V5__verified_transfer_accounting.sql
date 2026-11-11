ALTER TABLE user_file_stats ADD COLUMN verified_uploaded BIGINT NOT NULL DEFAULT 0 CHECK(verified_uploaded >= 0);
ALTER TABLE user_file_stats ADD COLUMN verified_downloaded BIGINT NOT NULL DEFAULT 0 CHECK(verified_downloaded >= 0);
