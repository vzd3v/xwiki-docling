-- Schema version 1. Derived data only. Original attachments remain authoritative.
CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY CHECK(version=1));
INSERT OR IGNORE INTO schema_version VALUES(1);
CREATE TABLE IF NOT EXISTS cache(
 cache_key TEXT PRIMARY KEY, content_hash TEXT NOT NULL, profile_hash TEXT NOT NULL,
 mime TEXT NOT NULL, input_file TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN('pending','running','ready','failed')),
 output_text TEXT, attempts INTEGER NOT NULL DEFAULT 0, available_at INTEGER NOT NULL DEFAULT 0,
 last_error TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS cache_pending ON cache(state,available_at);
CREATE TABLE IF NOT EXISTS owners(
 attachment_ref TEXT PRIMARY KEY, page_ref TEXT NOT NULL, version TEXT NOT NULL,
 cache_key TEXT NOT NULL REFERENCES cache(cache_key), updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS owners_cache ON owners(cache_key);
CREATE INDEX IF NOT EXISTS owners_page ON owners(page_ref,attachment_ref);
CREATE TABLE IF NOT EXISTS reindex(
 page_ref TEXT PRIMARY KEY, generation INTEGER NOT NULL DEFAULT 1,
 attempts INTEGER NOT NULL DEFAULT 0, available_at INTEGER NOT NULL DEFAULT 0,
 last_error TEXT, needs_index INTEGER NOT NULL DEFAULT 0
);
