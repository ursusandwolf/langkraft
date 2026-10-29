ALTER TABLE vocabulary_sync ADD COLUMN server_updated BIGINT NOT NULL DEFAULT 0;
ALTER TABLE vocabulary_sync ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT 0;
UPDATE vocabulary_sync SET server_updated = MAX(1, CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER), (SELECT COALESCE(MAX(server_updated), 0) + 1 FROM vocabulary_sync)) WHERE server_updated = 0;
