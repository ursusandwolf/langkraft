CREATE TABLE users (
    id VARCHAR(36) PRIMARY KEY NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL
);
CREATE UNIQUE INDEX users_email_unique ON users(email);
CREATE TABLE vocabulary_sync (
    id VARCHAR(36) PRIMARY KEY NOT NULL,
    user_id VARCHAR(36) NOT NULL REFERENCES users(id),
    word VARCHAR(255) NOT NULL,
    lemma VARCHAR(255), translation VARCHAR(1024), context_sentence TEXT NOT NULL,
    content_id VARCHAR(36), subtitle_line_id VARCHAR(36), added_at BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL, next_review_at BIGINT NOT NULL, interval_days BIGINT NOT NULL,
    ease_factor DOUBLE PRECISION NOT NULL, last_updated BIGINT NOT NULL
);
INSERT INTO users VALUES ('a', 'a@example.org', 'hash', 'A');
INSERT INTO vocabulary_sync VALUES ('w', 'a', 'Haus', 'Haus', 'house', 'Das Haus', 'content', 'subtitle', 1, 'LEARNING', 123, 8, 2.7, 9);
