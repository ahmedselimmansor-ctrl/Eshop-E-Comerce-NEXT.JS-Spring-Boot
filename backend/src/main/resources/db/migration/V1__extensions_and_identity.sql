-- ============================================================================
-- V1 — Extensions, identity, access control
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS btree_gin;

-- ---------------------------------------------------------------- users -----
CREATE TABLE users (
    id                      UUID PRIMARY KEY,
    email                   CITEXT       NOT NULL,
    phone                   VARCHAR(20),
    password_hash           TEXT,
    first_name              VARCHAR(80)  NOT NULL,
    last_name               VARCHAR(80),
    avatar_url              TEXT,
    email_verified          BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_verified          BOOLEAN      NOT NULL DEFAULT FALSE,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    locale                  VARCHAR(5)   NOT NULL DEFAULT 'ar',
    default_currency        VARCHAR(3)   NOT NULL DEFAULT 'EGP',
    marketing_opt_in        BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts   SMALLINT     NOT NULL DEFAULT 0,
    locked_until            TIMESTAMPTZ,
    last_login_at           TIMESTAMPTZ,
    version                 BIGINT       NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at              TIMESTAMPTZ,
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETED')),
    CONSTRAINT ck_users_locale CHECK (locale IN ('ar', 'en'))
);

-- Uniqueness applies only to live rows so a soft-deleted account frees its email.
CREATE UNIQUE INDEX uq_users_email ON users (email) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_phone ON users (phone) WHERE phone IS NOT NULL AND deleted_at IS NULL;
CREATE INDEX idx_users_status_created ON users (status, created_at DESC);
CREATE INDEX idx_users_name_trgm ON users USING gin ((first_name || ' ' || coalesce(last_name, '')) gin_trgm_ops);

COMMENT ON COLUMN users.password_hash IS 'Argon2id. NULL for OAuth-only accounts.';

-- ---------------------------------------------------------------- roles -----
CREATE TABLE roles (
    id              UUID PRIMARY KEY,
    name            VARCHAR(50)  NOT NULL UNIQUE,
    description_ar  TEXT         NOT NULL,
    description_en  TEXT,
    is_system       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE permissions (
    id              UUID PRIMARY KEY,
    name            VARCHAR(64)  NOT NULL UNIQUE,
    resource        VARCHAR(32)  NOT NULL,
    action          VARCHAR(32)  NOT NULL,
    description_ar  TEXT         NOT NULL,
    description_en  TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_permissions_resource ON permissions (resource);

CREATE TABLE role_permissions (
    role_id         UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id   UUID NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX idx_role_permissions_permission ON role_permissions (permission_id);

CREATE TABLE user_roles (
    user_id     UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by  UUID REFERENCES users (id) ON DELETE SET NULL,
    PRIMARY KEY (user_id, role_id)
);
CREATE INDEX idx_user_roles_role ON user_roles (role_id);

-- ------------------------------------------------------- refresh tokens -----
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,
    family_id       UUID         NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by_id  UUID REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    user_agent      TEXT,
    ip              INET,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id, created_at DESC);
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_expiry ON refresh_tokens (expires_at) WHERE revoked_at IS NULL;

COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 hex of the opaque token. The raw token is never stored.';
COMMENT ON COLUMN refresh_tokens.family_id IS 'Rotation family. Presenting an already-revoked token revokes the whole family (reuse detection).';

-- ------------------------------------------------------ oauth accounts -----
CREATE TABLE oauth_accounts (
    id                  UUID PRIMARY KEY,
    user_id             UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider            VARCHAR(20)  NOT NULL,
    provider_user_id    VARCHAR(191) NOT NULL,
    email               CITEXT,
    raw_profile         JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_oauth_provider CHECK (provider IN ('GOOGLE', 'APPLE', 'FACEBOOK')),
    CONSTRAINT uq_oauth_provider_user UNIQUE (provider, provider_user_id)
);
CREATE INDEX idx_oauth_accounts_user ON oauth_accounts (user_id);

-- ----------------------------------------------------- one-time tokens -----
CREATE TABLE one_time_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type         VARCHAR(30)  NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    code         VARCHAR(10),
    expires_at   TIMESTAMPTZ  NOT NULL,
    consumed_at  TIMESTAMPTZ,
    attempts     SMALLINT     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ott_type CHECK (type IN ('EMAIL_VERIFICATION', 'PASSWORD_RESET', 'PHONE_OTP'))
);
CREATE UNIQUE INDEX uq_one_time_tokens_hash ON one_time_tokens (token_hash);
CREATE INDEX idx_one_time_tokens_user_type ON one_time_tokens (user_id, type) WHERE consumed_at IS NULL;
CREATE INDEX idx_one_time_tokens_expiry ON one_time_tokens (expires_at);

-- ------------------------------------------------------------ addresses -----
CREATE TABLE addresses (
    id                    UUID PRIMARY KEY,
    user_id               UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label                 VARCHAR(40),
    recipient_name        VARCHAR(120) NOT NULL,
    phone                 VARCHAR(20)  NOT NULL,
    country_code          VARCHAR(2)   NOT NULL DEFAULT 'EG',
    governorate           VARCHAR(60)  NOT NULL,
    city                  VARCHAR(80)  NOT NULL,
    area                  VARCHAR(80),
    street                VARCHAR(160) NOT NULL,
    building              VARCHAR(40),
    apartment             VARCHAR(40),
    floor                 VARCHAR(20),
    landmark              VARCHAR(160),
    postal_code           VARCHAR(12),
    latitude              NUMERIC(9, 6),
    longitude             NUMERIC(9, 6),
    is_default_shipping   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_default_billing    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ
);
CREATE INDEX idx_addresses_user ON addresses (user_id) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_addresses_default_shipping ON addresses (user_id)
    WHERE is_default_shipping AND deleted_at IS NULL;
CREATE UNIQUE INDEX uq_addresses_default_billing ON addresses (user_id)
    WHERE is_default_billing AND deleted_at IS NULL;
