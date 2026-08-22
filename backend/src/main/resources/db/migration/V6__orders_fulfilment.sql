-- ============================================================================
-- V6 — Shipping, orders, fulfilment, returns
-- ============================================================================

CREATE TABLE shipping_zones (
    id             UUID PRIMARY KEY,
    name_ar        VARCHAR(120) NOT NULL,
    name_en        VARCHAR(120),
    governorates   JSONB        NOT NULL DEFAULT '[]'::jsonb,
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_shipping_zones_governorates ON shipping_zones USING gin (governorates);

CREATE TABLE shipping_methods (
    id                       UUID PRIMARY KEY,
    code                     VARCHAR(30)   NOT NULL UNIQUE,
    name_ar                  VARCHAR(120)  NOT NULL,
    name_en                  VARCHAR(120),
    description_ar           TEXT,
    description_en           TEXT,
    zone_id                  UUID REFERENCES shipping_zones (id) ON DELETE SET NULL,
    base_cost                NUMERIC(12,2) NOT NULL DEFAULT 0,
    cost_per_kg              NUMERIC(12,2) NOT NULL DEFAULT 0,
    free_shipping_threshold  NUMERIC(12,2),
    estimated_days_min       SMALLINT,
    estimated_days_max       SMALLINT,
    supports_cod             BOOLEAN       NOT NULL DEFAULT TRUE,
    is_active                BOOLEAN       NOT NULL DEFAULT TRUE,
    position                 INT           NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_shipping_days CHECK (estimated_days_max IS NULL OR estimated_days_min IS NULL
                                       OR estimated_days_max >= estimated_days_min)
);
CREATE INDEX idx_shipping_methods_zone ON shipping_methods (zone_id, is_active, position);

-- ---------------------------------------------------------------- orders ----
CREATE TABLE orders (
    id                       UUID PRIMARY KEY,
    order_number             VARCHAR(20)   NOT NULL UNIQUE,
    user_id                  UUID REFERENCES users (id) ON DELETE SET NULL,
    guest_email              CITEXT,
    guest_phone              VARCHAR(20),
    status                   VARCHAR(30)   NOT NULL DEFAULT 'PENDING_PAYMENT',
    payment_status           VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    fulfillment_status       VARCHAR(30)   NOT NULL DEFAULT 'UNFULFILLED',
    currency                 VARCHAR(3)    NOT NULL DEFAULT 'EGP',
    subtotal                 NUMERIC(12,2) NOT NULL,
    discount_total           NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_total                NUMERIC(12,2) NOT NULL DEFAULT 0,
    shipping_total           NUMERIC(12,2) NOT NULL DEFAULT 0,
    cod_fee                  NUMERIC(12,2) NOT NULL DEFAULT 0,
    grand_total              NUMERIC(12,2) NOT NULL,
    coupon_code              VARCHAR(40),
    shipping_address         JSONB         NOT NULL,
    billing_address          JSONB,
    shipping_method_id       UUID REFERENCES shipping_methods (id) ON DELETE SET NULL,
    shipping_method_name_ar  VARCHAR(120),
    shipping_method_name_en  VARCHAR(120),
    customer_note            TEXT,
    admin_note               TEXT,
    locale                   VARCHAR(5) NOT NULL DEFAULT 'ar',
    ip                       INET,
    user_agent               TEXT,
    placed_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    paid_at                  TIMESTAMPTZ,
    shipped_at               TIMESTAMPTZ,
    delivered_at             TIMESTAMPTZ,
    cancelled_at             TIMESTAMPTZ,
    cancel_reason            TEXT,
    version                  BIGINT        NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_orders_status CHECK (status IN
        ('PENDING_PAYMENT', 'PAID', 'PROCESSING', 'SHIPPED', 'DELIVERED',
         'CANCELLED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'FAILED')),
    CONSTRAINT ck_orders_payment_status CHECK (payment_status IN
        ('PENDING', 'AUTHORIZED', 'PAID', 'FAILED', 'REFUNDED', 'PARTIALLY_REFUNDED')),
    CONSTRAINT ck_orders_fulfillment_status CHECK (fulfillment_status IN
        ('UNFULFILLED', 'PARTIALLY_FULFILLED', 'FULFILLED', 'RETURNED')),
    CONSTRAINT ck_orders_owner CHECK (user_id IS NOT NULL OR guest_email IS NOT NULL),
    CONSTRAINT ck_orders_totals CHECK (grand_total >= 0 AND subtotal >= 0)
);
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);
CREATE INDEX idx_orders_status_created ON orders (status, created_at DESC);
CREATE INDEX idx_orders_payment_status ON orders (payment_status, created_at DESC);
CREATE INDEX idx_orders_placed_at ON orders (placed_at DESC);
CREATE INDEX idx_orders_guest_email ON orders (guest_email) WHERE guest_email IS NOT NULL;

COMMENT ON COLUMN orders.shipping_address IS
    'Frozen snapshot at placement. Editing the address book must never rewrite historical orders.';

CREATE TABLE order_items (
    id                 UUID PRIMARY KEY,
    order_id           UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id         UUID REFERENCES products (id) ON DELETE SET NULL,
    variant_id         UUID REFERENCES product_variants (id) ON DELETE SET NULL,
    sku                VARCHAR(64)   NOT NULL,
    name_ar            VARCHAR(255)  NOT NULL,
    name_en            VARCHAR(255),
    variant_label_ar   VARCHAR(255),
    variant_label_en   VARCHAR(255),
    image_url          TEXT,
    unit_price         NUMERIC(12,2) NOT NULL,
    quantity           INT           NOT NULL,
    discount_amount    NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_amount         NUMERIC(12,2) NOT NULL DEFAULT 0,
    line_total         NUMERIC(12,2) NOT NULL,
    fulfilled_quantity INT           NOT NULL DEFAULT 0,
    refunded_quantity  INT           NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_order_items_fulfilled CHECK (fulfilled_quantity BETWEEN 0 AND quantity),
    CONSTRAINT ck_order_items_refunded CHECK (refunded_quantity BETWEEN 0 AND quantity)
);
CREATE INDEX idx_order_items_order ON order_items (order_id);
CREATE INDEX idx_order_items_product ON order_items (product_id);

COMMENT ON TABLE order_items IS 'All descriptive columns are frozen snapshots — catalog edits must not alter order history.';

CREATE TABLE order_status_history (
    id               UUID PRIMARY KEY,
    order_id         UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    from_status      VARCHAR(30),
    to_status        VARCHAR(30) NOT NULL,
    note             TEXT,
    changed_by       UUID REFERENCES users (id) ON DELETE SET NULL,
    changed_by_type  VARCHAR(20) NOT NULL DEFAULT 'SYSTEM',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_osh_changed_by_type CHECK (changed_by_type IN ('CUSTOMER', 'ADMIN', 'SYSTEM', 'WEBHOOK'))
);
CREATE INDEX idx_order_status_history_order ON order_status_history (order_id, created_at DESC);

-- ------------------------------------------------------------- shipments ----
CREATE TABLE shipments (
    id               UUID PRIMARY KEY,
    order_id         UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    carrier          VARCHAR(60),
    tracking_number  VARCHAR(80),
    tracking_url     TEXT,
    status           VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    cost             NUMERIC(12,2),
    shipped_at       TIMESTAMPTZ,
    delivered_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_shipments_status CHECK (status IN
        ('PENDING', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED', 'RETURNED'))
);
CREATE INDEX idx_shipments_order ON shipments (order_id);
CREATE INDEX idx_shipments_tracking ON shipments (tracking_number);

CREATE TABLE shipment_items (
    id             UUID PRIMARY KEY,
    shipment_id    UUID NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    order_item_id  UUID NOT NULL REFERENCES order_items (id) ON DELETE CASCADE,
    quantity       INT  NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_shipment_items_quantity CHECK (quantity > 0),
    CONSTRAINT uq_shipment_items UNIQUE (shipment_id, order_item_id)
);

-- --------------------------------------------------------------- returns ----
CREATE TABLE returns (
    id            UUID PRIMARY KEY,
    order_id      UUID        NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    user_id       UUID REFERENCES users (id) ON DELETE SET NULL,
    return_number VARCHAR(20) NOT NULL UNIQUE,
    reason        VARCHAR(40) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    note          TEXT,
    admin_note    TEXT,
    resolved_by   UUID REFERENCES users (id) ON DELETE SET NULL,
    resolved_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_returns_status CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED', 'RECEIVED', 'REFUNDED')),
    CONSTRAINT ck_returns_reason CHECK (reason IN
        ('DAMAGED', 'WRONG_ITEM', 'NOT_AS_DESCRIBED', 'CHANGED_MIND', 'SIZE_ISSUE', 'DEFECTIVE', 'OTHER'))
);
CREATE INDEX idx_returns_order ON returns (order_id);
CREATE INDEX idx_returns_status ON returns (status, created_at DESC);

CREATE TABLE return_items (
    id             UUID PRIMARY KEY,
    return_id      UUID        NOT NULL REFERENCES returns (id) ON DELETE CASCADE,
    order_item_id  UUID        NOT NULL REFERENCES order_items (id) ON DELETE CASCADE,
    quantity       INT         NOT NULL,
    condition      VARCHAR(20),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_return_items_quantity CHECK (quantity > 0),
    CONSTRAINT ck_return_items_condition CHECK (condition IS NULL OR condition IN ('NEW', 'USED', 'DAMAGED')),
    CONSTRAINT uq_return_items UNIQUE (return_id, order_item_id)
);

-- Deferred FKs from earlier migrations, now that `orders` exists.
ALTER TABLE stock_reservations
    ADD CONSTRAINT fk_stock_reservations_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE;
ALTER TABLE stock_reservations
    ADD CONSTRAINT fk_stock_reservations_cart FOREIGN KEY (cart_id) REFERENCES carts (id) ON DELETE CASCADE;
ALTER TABLE coupon_redemptions
    ADD CONSTRAINT fk_coupon_redemptions_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE;
