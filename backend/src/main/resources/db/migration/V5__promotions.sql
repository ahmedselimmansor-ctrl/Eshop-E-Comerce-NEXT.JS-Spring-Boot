-- ============================================================================
-- V5 - Coupons and rule-driven promotions
-- ============================================================================

CREATE TABLE coupons (
    id                     UUID PRIMARY KEY,
    code                   VARCHAR(40)   NOT NULL UNIQUE,
    description_ar         TEXT,
    description_en         TEXT,
    type                   VARCHAR(20)   NOT NULL,
    value                  NUMERIC(12,2) NOT NULL,
    min_order_amount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    max_discount_amount    NUMERIC(12,2),
    usage_limit            INT,
    usage_limit_per_user   INT           NOT NULL DEFAULT 1,
    used_count             INT           NOT NULL DEFAULT 0,
    applies_to             VARCHAR(20)   NOT NULL DEFAULT 'ALL',
    first_order_only       BOOLEAN       NOT NULL DEFAULT FALSE,
    starts_at              TIMESTAMPTZ,
    ends_at                TIMESTAMPTZ,
    is_active              BOOLEAN       NOT NULL DEFAULT TRUE,
    version                BIGINT        NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_coupons_type CHECK (type IN ('PERCENTAGE', 'FIXED', 'FREE_SHIPPING')),
    CONSTRAINT ck_coupons_applies_to CHECK (applies_to IN ('ALL', 'CATEGORY', 'PRODUCT', 'BRAND')),
    CONSTRAINT ck_coupons_value CHECK (value >= 0),
    CONSTRAINT ck_coupons_percentage_range CHECK (type <> 'PERCENTAGE' OR value <= 100),
    CONSTRAINT ck_coupons_window CHECK (starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at),
    CONSTRAINT ck_coupons_code_upper CHECK (code = upper(code))
);
CREATE INDEX idx_coupons_active_window ON coupons (is_active, starts_at, ends_at);

CREATE TABLE coupon_targets (
    id           UUID PRIMARY KEY,
    coupon_id    UUID        NOT NULL REFERENCES coupons (id) ON DELETE CASCADE,
    target_type  VARCHAR(20) NOT NULL,
    target_id    UUID        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_coupon_target_type CHECK (target_type IN ('CATEGORY', 'PRODUCT', 'BRAND')),
    CONSTRAINT uq_coupon_targets UNIQUE (coupon_id, target_type, target_id)
);
CREATE INDEX idx_coupon_targets_target ON coupon_targets (target_type, target_id);

CREATE TABLE coupon_redemptions (
    id               UUID PRIMARY KEY,
    coupon_id        UUID          NOT NULL REFERENCES coupons (id) ON DELETE CASCADE,
    user_id          UUID REFERENCES users (id) ON DELETE SET NULL,
    order_id         UUID          NOT NULL,
    discount_amount  NUMERIC(12,2) NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_coupon_redemption_order UNIQUE (coupon_id, order_id)
);
CREATE INDEX idx_coupon_redemptions_user ON coupon_redemptions (coupon_id, user_id);

COMMENT ON TABLE coupon_redemptions IS
    'One row per successful application. usage_limit_per_user is enforced by counting rows here, not by trusting used_count.';

CREATE TABLE promotions (
    id             UUID PRIMARY KEY,
    name_ar        VARCHAR(160) NOT NULL,
    name_en        VARCHAR(160),
    type           VARCHAR(30)  NOT NULL,
    config         JSONB        NOT NULL,
    priority       INT          NOT NULL DEFAULT 0,
    is_stackable   BOOLEAN      NOT NULL DEFAULT FALSE,
    starts_at      TIMESTAMPTZ,
    ends_at        TIMESTAMPTZ,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_promotions_type CHECK (type IN ('BUY_X_GET_Y', 'TIERED_DISCOUNT', 'FLASH_SALE', 'BUNDLE'))
);
CREATE INDEX idx_promotions_active ON promotions (is_active, priority DESC, starts_at, ends_at);

COMMENT ON COLUMN promotions.config IS
    'Type-specific rule payload, e.g. BUY_X_GET_Y {"buyQuantity":2,"getQuantity":1,"productIds":[...]}; '
    'FLASH_SALE {"discountPercent":30,"productIds":[...]}; TIERED_DISCOUNT {"tiers":[{"minAmount":1000,"percent":5}]}.';
