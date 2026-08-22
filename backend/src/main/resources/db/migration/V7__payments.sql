-- ============================================================================
-- V7 — Payments, refunds, webhook idempotency
--
-- PCI scope note: card data never reaches this system. Paymob's iframe and
-- Fawry's hosted flow collect the PAN. We persist provider identifiers,
-- the brand, and the last four digits — nothing else.
-- ============================================================================

CREATE TABLE payments (
    id                   UUID PRIMARY KEY,
    order_id             UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    provider             VARCHAR(20)   NOT NULL,
    method               VARCHAR(20)   NOT NULL,
    provider_payment_id  VARCHAR(120),
    provider_order_id    VARCHAR(120),
    reference_code       VARCHAR(60),
    amount               NUMERIC(12,2) NOT NULL,
    currency             VARCHAR(3)    NOT NULL DEFAULT 'EGP',
    status               VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    card_last4           VARCHAR(4),
    card_brand           VARCHAR(20),
    idempotency_key      VARCHAR(80)   NOT NULL UNIQUE,
    failure_code         VARCHAR(60),
    failure_reason       TEXT,
    raw_response         JSONB,
    expires_at           TIMESTAMPTZ,
    authorized_at        TIMESTAMPTZ,
    captured_at          TIMESTAMPTZ,
    version              BIGINT        NOT NULL DEFAULT 0,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_payments_provider CHECK (provider IN ('PAYMOB', 'FAWRY', 'COD')),
    CONSTRAINT ck_payments_method CHECK (method IN ('CARD', 'WALLET', 'INSTALLMENT', 'KIOSK', 'COD')),
    CONSTRAINT ck_payments_status CHECK (status IN
        ('PENDING', 'AUTHORIZED', 'CAPTURED', 'FAILED', 'CANCELLED', 'EXPIRED', 'REFUNDED', 'PARTIALLY_REFUNDED')),
    CONSTRAINT ck_payments_amount CHECK (amount > 0)
);
CREATE INDEX idx_payments_order ON payments (order_id, created_at DESC);
CREATE INDEX idx_payments_provider_payment_id ON payments (provider, provider_payment_id);
CREATE INDEX idx_payments_provider_order_id ON payments (provider, provider_order_id);
CREATE INDEX idx_payments_reference_code ON payments (reference_code) WHERE reference_code IS NOT NULL;
CREATE INDEX idx_payments_status ON payments (status, created_at DESC);
CREATE INDEX idx_payments_expiry ON payments (expires_at) WHERE status = 'PENDING';

COMMENT ON COLUMN payments.raw_response IS 'Provider payload with sensitive keys redacted by PaymentPayloadRedactor before persistence.';

CREATE TABLE refunds (
    id                  UUID PRIMARY KEY,
    payment_id          UUID          NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    order_id            UUID          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    amount              NUMERIC(12,2) NOT NULL,
    reason              VARCHAR(60)   NOT NULL,
    note                TEXT,
    provider_refund_id  VARCHAR(120),
    idempotency_key     VARCHAR(80)   UNIQUE,
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    created_by          UUID REFERENCES users (id) ON DELETE SET NULL,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_refunds_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_refunds_amount CHECK (amount > 0)
);
CREATE INDEX idx_refunds_order ON refunds (order_id);
CREATE INDEX idx_refunds_payment ON refunds (payment_id);

CREATE TABLE payment_webhook_events (
    id               UUID PRIMARY KEY,
    provider         VARCHAR(20)  NOT NULL,
    event_id         VARCHAR(160) NOT NULL,
    event_type       VARCHAR(60),
    payload          JSONB        NOT NULL,
    signature        TEXT,
    signature_valid  BOOLEAN      NOT NULL DEFAULT FALSE,
    processed        BOOLEAN      NOT NULL DEFAULT FALSE,
    processed_at     TIMESTAMPTZ,
    error            TEXT,
    attempts         SMALLINT     NOT NULL DEFAULT 0,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_webhook_provider CHECK (provider IN ('PAYMOB', 'FAWRY')),
    CONSTRAINT uq_webhook_event UNIQUE (provider, event_id)
);
CREATE INDEX idx_webhook_events_unprocessed ON payment_webhook_events (created_at) WHERE NOT processed;

COMMENT ON TABLE payment_webhook_events IS
    'The UNIQUE (provider, event_id) constraint is the idempotency guard: a duplicate delivery '
    'hits the constraint, is answered 200, and is not reprocessed.';
