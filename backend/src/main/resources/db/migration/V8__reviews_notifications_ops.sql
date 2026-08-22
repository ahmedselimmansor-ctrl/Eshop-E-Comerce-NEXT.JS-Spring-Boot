-- ============================================================================
-- V8 — Reviews, notifications, audit, outbox, idempotency, settings
-- ============================================================================

CREATE TABLE reviews (
    id                    UUID PRIMARY KEY,
    product_id            UUID         NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    user_id               UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    order_item_id         UUID REFERENCES order_items (id) ON DELETE SET NULL,
    rating                SMALLINT     NOT NULL,
    title                 VARCHAR(120),
    body                  TEXT,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    is_verified_purchase  BOOLEAN      NOT NULL DEFAULT FALSE,
    helpful_count         INT          NOT NULL DEFAULT 0,
    admin_reply           TEXT,
    admin_replied_at      TIMESTAMPTZ,
    admin_replied_by      UUID REFERENCES users (id) ON DELETE SET NULL,
    rejection_reason      TEXT,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_reviews_rating CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_reviews_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT uq_reviews_product_user UNIQUE (product_id, user_id)
);
CREATE INDEX idx_reviews_product_approved ON reviews (product_id, created_at DESC) WHERE status = 'APPROVED';
CREATE INDEX idx_reviews_status ON reviews (status, created_at DESC);
CREATE INDEX idx_reviews_user ON reviews (user_id, created_at DESC);

CREATE TABLE review_media (
    id         UUID PRIMARY KEY,
    review_id  UUID        NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    url        TEXT        NOT NULL,
    position   INT         NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_review_media_review ON review_media (review_id, position);

CREATE TABLE review_votes (
    review_id   UUID        NOT NULL REFERENCES reviews (id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    is_helpful  BOOLEAN     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (review_id, user_id)
);

-- --------------------------------------------------------- notifications ----
CREATE TABLE notifications (
    id          UUID PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(40)  NOT NULL,
    title_ar    VARCHAR(200) NOT NULL,
    title_en    VARCHAR(200),
    body_ar     TEXT,
    body_en     TEXT,
    data        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    channel     VARCHAR(20)  NOT NULL DEFAULT 'IN_APP',
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_notifications_channel CHECK (channel IN ('IN_APP', 'EMAIL', 'PUSH', 'SMS'))
);
CREATE INDEX idx_notifications_user_created ON notifications (user_id, created_at DESC);
CREATE INDEX idx_notifications_unread ON notifications (user_id) WHERE read_at IS NULL;

CREATE TABLE device_tokens (
    id            UUID PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token         TEXT        NOT NULL UNIQUE,
    platform      VARCHAR(10) NOT NULL,
    locale        VARCHAR(5)  NOT NULL DEFAULT 'ar',
    is_active     BOOLEAN     NOT NULL DEFAULT TRUE,
    last_seen_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_device_platform CHECK (platform IN ('IOS', 'ANDROID', 'WEB'))
);
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id) WHERE is_active;

-- ------------------------------------------------------------ audit logs ----
CREATE TABLE audit_logs (
    id           UUID PRIMARY KEY,
    actor_id     UUID,
    actor_type   VARCHAR(20)  NOT NULL DEFAULT 'SYSTEM',
    actor_email  CITEXT,
    action       VARCHAR(60)  NOT NULL,
    entity_type  VARCHAR(60)  NOT NULL,
    entity_id    UUID,
    before       JSONB,
    after        JSONB,
    ip           INET,
    user_agent   TEXT,
    request_id   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_actor_type CHECK (actor_type IN ('USER', 'ADMIN', 'SYSTEM'))
);
CREATE INDEX idx_audit_entity ON audit_logs (entity_type, entity_id, created_at DESC);
CREATE INDEX idx_audit_actor ON audit_logs (actor_id, created_at DESC);
CREATE INDEX idx_audit_created ON audit_logs (created_at DESC);

COMMENT ON TABLE audit_logs IS 'Append-only. No UPDATE or DELETE grant is issued to the application role.';

-- ---------------------------------------------------- transactional outbox --
CREATE TABLE outbox_events (
    id              UUID PRIMARY KEY,
    aggregate_type  VARCHAR(40) NOT NULL,
    aggregate_id    UUID        NOT NULL,
    event_type      VARCHAR(60) NOT NULL,
    payload         JSONB       NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        SMALLINT    NOT NULL DEFAULT 0,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_aggregate ON outbox_events (aggregate_type, aggregate_id);

COMMENT ON TABLE outbox_events IS
    'Written inside the same transaction as the domain change, then relayed to Pub/Sub by OutboxRelay. '
    'Guarantees at-least-once delivery without a distributed transaction.';

-- --------------------------------------------------------- idempotency ------
CREATE TABLE idempotency_keys (
    key              VARCHAR(80) PRIMARY KEY,
    user_id          UUID,
    endpoint         VARCHAR(120) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    response_status  SMALLINT,
    response_body    JSONB,
    state            VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_idempotency_state CHECK (state IN ('IN_PROGRESS', 'COMPLETED'))
);
CREATE INDEX idx_idempotency_expiry ON idempotency_keys (expires_at);

COMMENT ON COLUMN idempotency_keys.request_hash IS
    'SHA-256 of the canonical request body. A replay with the same key but a different body returns 409 IDEMPOTENCY_CONFLICT.';

-- ------------------------------------------------------------- settings -----
CREATE TABLE settings (
    key          VARCHAR(80) PRIMARY KEY,
    value        JSONB       NOT NULL,
    description  TEXT,
    is_public    BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_by   UUID REFERENCES users (id) ON DELETE SET NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_settings_public ON settings (key) WHERE is_public;

-- -------------------------------------------------------- search queries ----
CREATE TABLE search_queries (
    id                 UUID PRIMARY KEY,
    query              TEXT       NOT NULL,
    normalized_query   TEXT       NOT NULL,
    locale             VARCHAR(5) NOT NULL DEFAULT 'ar',
    user_id            UUID REFERENCES users (id) ON DELETE SET NULL,
    results_count      INT        NOT NULL DEFAULT 0,
    clicked_product_id UUID REFERENCES products (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_search_queries_normalized ON search_queries (normalized_query, created_at DESC);
CREATE INDEX idx_search_queries_created ON search_queries (created_at DESC);
