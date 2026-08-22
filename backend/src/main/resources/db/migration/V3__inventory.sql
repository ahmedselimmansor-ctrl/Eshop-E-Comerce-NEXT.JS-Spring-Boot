-- ============================================================================
-- V3 — Inventory: warehouses, stock levels, append-only movement ledger,
--      and time-bounded reservations that back the checkout flow.
-- ============================================================================

CREATE TABLE warehouses (
    id           UUID PRIMARY KEY,
    code         VARCHAR(20)  NOT NULL UNIQUE,
    name_ar      VARCHAR(120) NOT NULL,
    name_en      VARCHAR(120),
    governorate  VARCHAR(60),
    city         VARCHAR(80),
    address      TEXT,
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    priority     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON COLUMN warehouses.priority IS 'Lower value is picked first when allocating stock across warehouses.';

CREATE TABLE inventory_items (
    id                 UUID PRIMARY KEY,
    product_id         UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    variant_id         UUID REFERENCES product_variants (id) ON DELETE CASCADE,
    warehouse_id       UUID        NOT NULL REFERENCES warehouses (id) ON DELETE RESTRICT,
    quantity_on_hand   INT         NOT NULL DEFAULT 0,
    quantity_reserved  INT         NOT NULL DEFAULT 0,
    reorder_level      INT         NOT NULL DEFAULT 5,
    version            BIGINT      NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_inventory_on_hand CHECK (quantity_on_hand >= 0),
    CONSTRAINT ck_inventory_reserved CHECK (quantity_reserved >= 0),
    CONSTRAINT ck_inventory_reserved_lte_on_hand CHECK (quantity_reserved <= quantity_on_hand)
);

-- A NULL variant_id means "simple product"; coalesce keeps the uniqueness rule
-- working for both shapes in a single index.
CREATE UNIQUE INDEX uq_inventory_items
    ON inventory_items (product_id, coalesce(variant_id, '00000000-0000-0000-0000-000000000000'::uuid), warehouse_id);
CREATE INDEX idx_inventory_items_variant ON inventory_items (variant_id);
CREATE INDEX idx_inventory_items_low_stock
    ON inventory_items (warehouse_id)
    WHERE quantity_on_hand - quantity_reserved <= reorder_level;

COMMENT ON TABLE inventory_items IS 'Available stock = quantity_on_hand - quantity_reserved. Never read on_hand alone.';

CREATE TABLE inventory_movements (
    id                 UUID PRIMARY KEY,
    inventory_item_id  UUID        NOT NULL REFERENCES inventory_items (id) ON DELETE CASCADE,
    type               VARCHAR(20) NOT NULL,
    quantity           INT         NOT NULL,
    quantity_after     INT,
    reference_type     VARCHAR(30),
    reference_id       UUID,
    note               TEXT,
    created_by         UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_movement_type CHECK (type IN
        ('RECEIPT', 'SALE', 'RETURN', 'ADJUSTMENT', 'RESERVATION', 'RELEASE', 'DAMAGE', 'TRANSFER')),
    CONSTRAINT ck_movement_quantity CHECK (quantity <> 0)
);
CREATE INDEX idx_inventory_movements_item ON inventory_movements (inventory_item_id, created_at DESC);
CREATE INDEX idx_inventory_movements_reference ON inventory_movements (reference_type, reference_id);

COMMENT ON TABLE inventory_movements IS 'Append-only ledger. Rows are never updated or deleted — corrections are new ADJUSTMENT rows.';

CREATE TABLE stock_reservations (
    id                 UUID PRIMARY KEY,
    order_id           UUID,
    cart_id            UUID,
    inventory_item_id  UUID        NOT NULL REFERENCES inventory_items (id) ON DELETE CASCADE,
    quantity           INT         NOT NULL,
    status             VARCHAR(20) NOT NULL DEFAULT 'HELD',
    expires_at         TIMESTAMPTZ NOT NULL,
    released_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_reservation_status CHECK (status IN ('HELD', 'COMMITTED', 'RELEASED')),
    CONSTRAINT ck_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_reservation_owner CHECK (order_id IS NOT NULL OR cart_id IS NOT NULL)
);
CREATE INDEX idx_stock_reservations_sweep ON stock_reservations (expires_at) WHERE status = 'HELD';
CREATE INDEX idx_stock_reservations_order ON stock_reservations (order_id);
CREATE INDEX idx_stock_reservations_item ON stock_reservations (inventory_item_id);

COMMENT ON TABLE stock_reservations IS
    'HELD rows past expires_at are swept back by ReservationSweeper; COMMITTED rows became a SALE movement.';
