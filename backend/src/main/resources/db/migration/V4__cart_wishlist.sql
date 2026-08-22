-- ============================================================================
-- V4 - Cart (guest + authenticated) and wishlists
-- ============================================================================

CREATE TABLE carts (
    id              UUID PRIMARY KEY,
    user_id         UUID REFERENCES users (id) ON DELETE CASCADE,
    session_id      VARCHAR(64),
    currency        VARCHAR(3)    NOT NULL DEFAULT 'EGP',
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    coupon_code     VARCHAR(40),
    subtotal        NUMERIC(12,2) NOT NULL DEFAULT 0,
    discount_total  NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_total       NUMERIC(12,2) NOT NULL DEFAULT 0,
    shipping_total  NUMERIC(12,2) NOT NULL DEFAULT 0,
    grand_total     NUMERIC(12,2) NOT NULL DEFAULT 0,
    item_count      INT           NOT NULL DEFAULT 0,
    expires_at      TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_carts_status CHECK (status IN ('ACTIVE', 'MERGED', 'CONVERTED', 'ABANDONED')),
    CONSTRAINT ck_carts_owner CHECK (user_id IS NOT NULL OR session_id IS NOT NULL)
);

-- Exactly one live cart per identity. Guest carts are keyed by session_id until
-- login, at which point CartService.merge() folds them into the user's cart.
CREATE UNIQUE INDEX uq_carts_active_user ON carts (user_id) WHERE status = 'ACTIVE' AND user_id IS NOT NULL;
CREATE UNIQUE INDEX uq_carts_active_session ON carts (session_id) WHERE status = 'ACTIVE' AND session_id IS NOT NULL;
CREATE INDEX idx_carts_expiry ON carts (expires_at) WHERE status = 'ACTIVE';
CREATE INDEX idx_carts_abandoned ON carts (updated_at) WHERE status = 'ACTIVE' AND item_count > 0;

CREATE TABLE cart_items (
    id           UUID PRIMARY KEY,
    cart_id      UUID          NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    product_id   UUID          NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    variant_id   UUID REFERENCES product_variants (id) ON DELETE CASCADE,
    quantity     INT           NOT NULL,
    unit_price   NUMERIC(12,2) NOT NULL,
    snapshot     JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_cart_items_quantity CHECK (quantity BETWEEN 1 AND 999)
);
CREATE UNIQUE INDEX uq_cart_items
    ON cart_items (cart_id, product_id, coalesce(variant_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_cart_items_product ON cart_items (product_id);

COMMENT ON COLUMN cart_items.unit_price IS
    'Price captured when the item was added. Re-validated against the catalog at checkout - a change surfaces as PRICE_CHANGED.';
COMMENT ON COLUMN cart_items.snapshot IS 'Display fields {nameAr,nameEn,imageUrl,sku,slug,variantLabelAr,variantLabelEn}.';

-- --------------------------------------------------------------- wishlist ---
CREATE TABLE wishlists (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(60) NOT NULL DEFAULT 'default',
    is_public    BOOLEAN     NOT NULL DEFAULT FALSE,
    share_token  VARCHAR(32) UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_wishlists_user_name UNIQUE (user_id, name)
);

CREATE TABLE wishlist_items (
    id           UUID PRIMARY KEY,
    wishlist_id  UUID        NOT NULL REFERENCES wishlists (id) ON DELETE CASCADE,
    product_id   UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    variant_id   UUID REFERENCES product_variants (id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uq_wishlist_items
    ON wishlist_items (wishlist_id, product_id, coalesce(variant_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_wishlist_items_product ON wishlist_items (product_id);
