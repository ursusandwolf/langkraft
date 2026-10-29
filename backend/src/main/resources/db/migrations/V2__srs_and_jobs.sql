ALTER TABLE vocabulary_sync ADD COLUMN lapse_count INT NOT NULL DEFAULT 0;
ALTER TABLE vocabulary_sync ADD COLUMN tags TEXT NOT NULL DEFAULT '[]';
CREATE TABLE IF NOT EXISTS ingestion_jobs (
    job_id VARCHAR(36) PRIMARY KEY NOT NULL,
    status VARCHAR(50) NOT NULL,
    url TEXT NOT NULL,
    content_json TEXT,
    error TEXT,
    created_at BIGINT NOT NULL,
    last_updated_at BIGINT NOT NULL
);
