-- Phase 7: private files, share links with an access log, and end-to-end encrypted (E2EE) files.

CREATE TABLE user_files (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Display name. For E2EE files this is an optional plaintext label (the real name is inside the ciphertext).
    name          VARCHAR(255) NOT NULL,
    -- Server-determined from magic bytes (never the client's Content-Type). E2EE files are application/octet-stream.
    content_type  VARCHAR(120) NOT NULL,
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes >= 0),
    storage_key   VARCHAR(200) NOT NULL UNIQUE,
    sha256        CHAR(64)     NOT NULL,
    e2ee          BOOLEAN      NOT NULL DEFAULT false,
    scan_status   VARCHAR(10)  NOT NULL CHECK (scan_status IN ('CLEAN', 'REJECTED', 'UNSCANNED')),
    scan_detail   VARCHAR(300),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);
CREATE INDEX ix_user_files_owner ON user_files (owner_id, id DESC) WHERE deleted_at IS NULL;

CREATE TABLE file_share_links (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_id         BIGINT       NOT NULL REFERENCES user_files (id) ON DELETE CASCADE,
    created_by      BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Only the SHA-256 of the token is stored; the owner sees the full link once, at creation.
    token_hash      CHAR(64)     NOT NULL UNIQUE,
    token_hint      VARCHAR(8)   NOT NULL,
    expires_at      TIMESTAMPTZ,
    password_hash   VARCHAR(100),
    max_downloads   INTEGER      CHECK (max_downloads IS NULL OR max_downloads > 0),
    download_count  INTEGER      NOT NULL DEFAULT 0,
    -- E2EE links only: FRAGMENT (key in the URL #fragment, never sent to the server) or PASSPHRASE
    -- (key wrapped with a PBKDF2-derived key in the browser; the server stores only the wrapped key).
    key_mode        VARCHAR(10)  CHECK (key_mode IN ('FRAGMENT', 'PASSPHRASE')),
    wrapped_key     VARCHAR(200),
    kdf_salt        VARCHAR(100),
    kdf_iterations  INTEGER,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    CHECK (key_mode IS DISTINCT FROM 'PASSPHRASE' OR (wrapped_key IS NOT NULL AND kdf_salt IS NOT NULL AND kdf_iterations IS NOT NULL))
);
CREATE INDEX ix_file_share_links_file ON file_share_links (file_id, id DESC);

-- Access log. No raw IP addresses: visitor_hash is a truncated HMAC of (day, IP) with a server-side salt,
-- so it groups one visitor's attempts within a day and can't be reversed or joined across days.
CREATE TABLE file_share_access (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    link_id       BIGINT      NOT NULL REFERENCES file_share_links (id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    viewer_id     BIGINT      REFERENCES users (id) ON DELETE SET NULL,
    visitor_hash  VARCHAR(16),
    event         VARCHAR(16) NOT NULL CHECK (event IN ('VIEWED', 'DOWNLOADED', 'WRONG_PASSWORD', 'LOCKED',
                                                        'EXPIRED', 'REVOKED', 'LIMIT_REACHED', 'FILE_DELETED'))
);
CREATE INDEX ix_file_share_access_link ON file_share_access (link_id, id DESC);
CREATE INDEX ix_file_share_access_failures ON file_share_access (link_id, created_at) WHERE event = 'WRONG_PASSWORD';
