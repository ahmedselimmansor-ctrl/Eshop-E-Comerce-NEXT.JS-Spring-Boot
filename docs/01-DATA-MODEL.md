# Eshop - Data Model (PostgreSQL 16)

Authoritative schema. Flyway migrations in `backend/src/main/resources/db/migration/` MUST match this exactly.

## Global rules
- Every PK is `UUID` (application-generated **UUIDv7** for index locality - `com.eshop.common.Uuid7`).
- Every table has `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`; mutable tables also have `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`.
- Soft-deletable tables have `deleted_at TIMESTAMPTZ NULL` and a partial index `WHERE deleted_at IS NULL`.
- Money: `NUMERIC(12,2)` + a sibling `currency CHAR(3)` on the aggregate root. Never `float`.
- Timestamps are `TIMESTAMPTZ`, stored UTC.
- Bilingual text: `<field>_ar TEXT` + `<field>_en TEXT`. `_ar` is `NOT NULL`, `_en` nullable.
- Enums are stored as `VARCHAR` + `CHECK` constraint (not PG enum types - cheaper to evolve). Java side uses `@Enumerated(STRING)`.
- Aggregate roots that can be concurrently mutated carry `version BIGINT NOT NULL DEFAULT 0` (`@Version`).
- Extensions: `citext`, `pg_trgm`, `unaccent`, `btree_gin`.

---

## V1 - Identity & access

**users**
`id` · `email CITEXT UNIQUE NOT NULL` · `phone VARCHAR(20) UNIQUE` · `password_hash TEXT` (null for OAuth-only) · `first_name VARCHAR(80) NOT NULL` · `last_name VARCHAR(80)` · `avatar_url TEXT` · `email_verified BOOL DEFAULT false` · `phone_verified BOOL DEFAULT false` · `status VARCHAR(20) CHECK IN (ACTIVE,SUSPENDED,DELETED) DEFAULT 'ACTIVE'` · `locale VARCHAR(5) DEFAULT 'ar'` · `default_currency CHAR(3) DEFAULT 'EGP'` · `marketing_opt_in BOOL DEFAULT false` · `failed_login_attempts SMALLINT DEFAULT 0` · `locked_until TIMESTAMPTZ` · `last_login_at` · `created_at` · `updated_at` · `deleted_at`
Indexes: `idx_users_email (email) WHERE deleted_at IS NULL`, `idx_users_phone`, `idx_users_status_created`.

**roles** - `id` · `name VARCHAR(50) UNIQUE` · `description_ar` · `description_en` · `is_system BOOL DEFAULT false`
Seeded: `CUSTOMER`, `SUPPORT`, `CATALOG_MANAGER`, `ORDER_MANAGER`, `ADMIN`, `SUPER_ADMIN`.

**permissions** - `id` · `name VARCHAR(64) UNIQUE` · `resource VARCHAR(32)` · `action VARCHAR(32)` · `description_ar/en`
Seeded (`resource:action`): `product:read|write|delete`, `category:read|write`, `brand:read|write`, `attribute:write`, `inventory:read|write`, `order:read|write|cancel|refund|fulfill`, `customer:read|write|suspend`, `coupon:read|write`, `promotion:write`, `review:moderate`, `shipping:write`, `media:write`, `report:read`, `user:read|write`, `role:write`, `settings:write`, `audit:read`.

**role_permissions** (`role_id`,`permission_id`) PK composite.
**user_roles** (`user_id`,`role_id`,`granted_at`,`granted_by`) PK composite.

**refresh_tokens** - `id` · `user_id FK` · `token_hash VARCHAR(64) UNIQUE` (SHA-256) · `family_id UUID NOT NULL` · `expires_at` · `revoked_at` · `replaced_by_id` · `user_agent TEXT` · `ip INET` · `created_at`
Reuse detection: presenting a token with `revoked_at IS NOT NULL` revokes the entire `family_id`.

**oauth_accounts** - `id` · `user_id FK` · `provider VARCHAR(20) CHECK IN (GOOGLE,APPLE,FACEBOOK)` · `provider_user_id VARCHAR(191)` · `email` · `raw_profile JSONB` · UNIQUE(`provider`,`provider_user_id`).

**one_time_tokens** - `id` · `user_id FK` · `type VARCHAR(30) CHECK IN (EMAIL_VERIFICATION,PASSWORD_RESET,PHONE_OTP)` · `token_hash VARCHAR(64)` · `code VARCHAR(10)` (OTP) · `expires_at` · `consumed_at` · `attempts SMALLINT DEFAULT 0`.

**addresses** - `id` · `user_id FK` · `label VARCHAR(40)` · `recipient_name VARCHAR(120) NOT NULL` · `phone VARCHAR(20) NOT NULL` · `country_code CHAR(2) DEFAULT 'EG'` · `governorate VARCHAR(60) NOT NULL` · `city VARCHAR(80) NOT NULL` · `area VARCHAR(80)` · `street VARCHAR(160) NOT NULL` · `building VARCHAR(40)` · `apartment VARCHAR(40)` · `floor VARCHAR(20)` · `landmark VARCHAR(160)` · `postal_code VARCHAR(12)` · `latitude NUMERIC(9,6)` · `longitude NUMERIC(9,6)` · `is_default_shipping BOOL` · `is_default_billing BOOL` · `created_at` · `updated_at` · `deleted_at`
Partial unique: only one `is_default_shipping = true` per user.

---

## V2 - Catalog

**categories** - `id` · `parent_id FK self` · `slug VARCHAR(140) UNIQUE` · `name_ar/name_en` · `description_ar/en` · `image_url` · `icon VARCHAR(60)` · `position INT DEFAULT 0` · `is_active BOOL DEFAULT true` · `path TEXT` (materialised `/root/child/leaf`) · `level SMALLINT` · `product_count INT DEFAULT 0` · `seo_title_ar/en` · `seo_description_ar/en` · timestamps
Index: `idx_categories_path (path text_pattern_ops)`, `idx_categories_parent_position`.

**brands** - `id` · `slug UNIQUE` · `name_ar/en` · `logo_url` · `description_ar/en` · `website` · `is_active` · `is_featured` · `position` · timestamps.

**attributes** - `id` · `code VARCHAR(50) UNIQUE` · `name_ar/en` · `type VARCHAR(20) CHECK IN (TEXT,NUMBER,BOOLEAN,SELECT,MULTISELECT,COLOR)` · `is_variant_axis BOOL DEFAULT false` · `is_filterable BOOL DEFAULT true` · `is_searchable BOOL DEFAULT false` · `is_required BOOL DEFAULT false` · `unit VARCHAR(20)` · `position INT` · timestamps.

**attribute_values** - `id` · `attribute_id FK` · `code VARCHAR(60)` · `value_ar/value_en` · `hex_color CHAR(7)` · `image_url` · `position` · UNIQUE(`attribute_id`,`code`).

**products** - `id` · `sku VARCHAR(64) UNIQUE` · `slug VARCHAR(180) UNIQUE` · `name_ar/name_en` · `short_description_ar/en` · `description_ar/en` (HTML, sanitised) · `brand_id FK` · `primary_category_id FK` · `status VARCHAR(20) CHECK IN (DRAFT,ACTIVE,ARCHIVED) DEFAULT 'DRAFT'` · `type VARCHAR(20) CHECK IN (SIMPLE,VARIABLE) DEFAULT 'SIMPLE'` · `base_price NUMERIC(12,2) NOT NULL CHECK >= 0` · `compare_at_price NUMERIC(12,2)` · `cost_price NUMERIC(12,2)` · `currency CHAR(3) DEFAULT 'EGP'` · `tax_class VARCHAR(20) DEFAULT 'STANDARD'` · `tax_rate NUMERIC(5,4) DEFAULT 0.14` · `weight_grams INT` · `length_mm/width_mm/height_mm INT` · `is_featured BOOL` · `is_digital BOOL DEFAULT false` · `requires_shipping BOOL DEFAULT true` · `rating_avg NUMERIC(2,1) DEFAULT 0` · `rating_count INT DEFAULT 0` · `view_count BIGINT DEFAULT 0` · `sold_count INT DEFAULT 0` · `seo_title_ar/en` · `seo_description_ar/en` · `published_at` · `version` · timestamps · `deleted_at`
Indexes: `idx_products_status_published`, `idx_products_brand`, `idx_products_primary_category`, `idx_products_name_trgm (name_ar gin_trgm_ops)`, `idx_products_featured WHERE is_featured`.

**product_categories** (`product_id`,`category_id`) PK composite - secondary categories.

**product_images** - `id` · `product_id FK` · `variant_id FK NULL` · `url TEXT NOT NULL` · `alt_ar/alt_en` · `position INT` · `is_primary BOOL` · `width INT` · `height INT` · `blurhash VARCHAR(64)` · `created_at`.

**product_attribute_values** - `id` · `product_id FK` · `attribute_id FK` · `attribute_value_id FK NULL` · `custom_value TEXT` · UNIQUE(`product_id`,`attribute_id`,`attribute_value_id`).

**product_variants** - `id` · `product_id FK` · `sku VARCHAR(64) UNIQUE` · `barcode VARCHAR(64)` · `price NUMERIC(12,2) NOT NULL` · `compare_at_price` · `cost_price` · `weight_grams` · `image_url` · `position` · `is_active BOOL DEFAULT true` · `version` · timestamps.

**variant_attribute_values** (`variant_id`,`attribute_id`,`attribute_value_id`) PK composite - the variant axes (e.g. Color=Red, Size=L).

---

## V3 - Inventory

**warehouses** - `id` · `code VARCHAR(20) UNIQUE` · `name_ar/en` · `governorate` · `city` · `address` · `is_active` · `priority INT` · timestamps.

**inventory_items** - `id` · `variant_id FK NULL` · `product_id FK NOT NULL` · `warehouse_id FK` · `quantity_on_hand INT NOT NULL DEFAULT 0 CHECK >= 0` · `quantity_reserved INT NOT NULL DEFAULT 0 CHECK >= 0` · `reorder_level INT DEFAULT 5` · `version` · `updated_at`
UNIQUE(`product_id`,`variant_id`,`warehouse_id`). **Available = `quantity_on_hand - quantity_reserved`.**
`CHECK (quantity_reserved <= quantity_on_hand)`.

**inventory_movements** - `id` · `inventory_item_id FK` · `type VARCHAR(20) CHECK IN (RECEIPT,SALE,RETURN,ADJUSTMENT,RESERVATION,RELEASE,DAMAGE,TRANSFER)` · `quantity INT NOT NULL` (signed) · `quantity_after INT` · `reference_type VARCHAR(30)` · `reference_id UUID` · `note TEXT` · `created_by FK users` · `created_at`
Append-only ledger - never updated or deleted.

**stock_reservations** - `id` · `order_id FK NULL` · `cart_id FK NULL` · `inventory_item_id FK` · `quantity INT` · `status VARCHAR(20) CHECK IN (HELD,COMMITTED,RELEASED)` · `expires_at TIMESTAMPTZ NOT NULL` · `created_at`
Index on `(status, expires_at)` for the release job.

---

## V4 - Cart & wishlist

**carts** - `id` · `user_id FK NULL` · `session_id VARCHAR(64) NULL` (guest) · `currency CHAR(3) DEFAULT 'EGP'` · `status VARCHAR(20) CHECK IN (ACTIVE,MERGED,CONVERTED,ABANDONED) DEFAULT 'ACTIVE'` · `coupon_code VARCHAR(40)` · `subtotal/discount_total/tax_total/shipping_total/grand_total NUMERIC(12,2) DEFAULT 0` · `item_count INT DEFAULT 0` · `expires_at` · `version` · timestamps
`CHECK (user_id IS NOT NULL OR session_id IS NOT NULL)`. Partial unique: one `ACTIVE` cart per `user_id`, one per `session_id`.

**cart_items** - `id` · `cart_id FK ON DELETE CASCADE` · `product_id FK` · `variant_id FK NULL` · `quantity INT CHECK BETWEEN 1 AND 999` · `unit_price NUMERIC(12,2)` (snapshot) · `snapshot JSONB` (`{nameAr,nameEn,imageUrl,sku,slug}`) · timestamps · UNIQUE(`cart_id`,`product_id`,`variant_id`).

**wishlists** - `id` · `user_id FK` · `name VARCHAR(60) DEFAULT 'default'` · `is_public BOOL` · `share_token VARCHAR(32) UNIQUE` · timestamps.
**wishlist_items** - `id` · `wishlist_id FK` · `product_id FK` · `variant_id FK NULL` · `created_at` · UNIQUE(`wishlist_id`,`product_id`,`variant_id`).

---

## V5 - Promotions

**coupons** - `id` · `code VARCHAR(40) UNIQUE` (uppercase) · `description_ar/en` · `type VARCHAR(20) CHECK IN (PERCENTAGE,FIXED,FREE_SHIPPING)` · `value NUMERIC(12,2)` · `min_order_amount NUMERIC(12,2) DEFAULT 0` · `max_discount_amount NUMERIC(12,2)` · `usage_limit INT` · `usage_limit_per_user INT DEFAULT 1` · `used_count INT DEFAULT 0` · `applies_to VARCHAR(20) CHECK IN (ALL,CATEGORY,PRODUCT,BRAND) DEFAULT 'ALL'` · `first_order_only BOOL DEFAULT false` · `starts_at` · `ends_at` · `is_active` · `version` · timestamps.

**coupon_targets** - `id` · `coupon_id FK` · `target_type VARCHAR(20)` · `target_id UUID`.
**coupon_redemptions** - `id` · `coupon_id FK` · `user_id FK NULL` · `order_id FK` · `discount_amount NUMERIC(12,2)` · `created_at` · UNIQUE(`coupon_id`,`order_id`).

**promotions** - `id` · `name_ar/en` · `type VARCHAR(30) CHECK IN (BUY_X_GET_Y,TIERED_DISCOUNT,FLASH_SALE,BUNDLE)` · `config JSONB NOT NULL` · `priority INT DEFAULT 0` · `is_stackable BOOL DEFAULT false` · `starts_at` · `ends_at` · `is_active` · timestamps.

---

## V6 - Orders & fulfilment

**shipping_zones** - `id` · `name_ar/en` · `governorates JSONB` (array) · `is_active` · timestamps.
**shipping_methods** - `id` · `code VARCHAR(30) UNIQUE` · `name_ar/en` · `description_ar/en` · `zone_id FK NULL` · `base_cost NUMERIC(12,2)` · `cost_per_kg NUMERIC(12,2) DEFAULT 0` · `free_shipping_threshold NUMERIC(12,2)` · `estimated_days_min/max SMALLINT` · `supports_cod BOOL DEFAULT true` · `is_active` · `position` · timestamps.

**orders** - `id` · `order_number VARCHAR(20) UNIQUE` (`EG-YYMMDD-XXXXX`) · `user_id FK NULL` · `guest_email CITEXT NULL` · `guest_phone VARCHAR(20)` · `status VARCHAR(30) CHECK IN (PENDING_PAYMENT,PAID,PROCESSING,SHIPPED,DELIVERED,CANCELLED,REFUNDED,PARTIALLY_REFUNDED,FAILED)` · `payment_status VARCHAR(30) CHECK IN (PENDING,AUTHORIZED,PAID,FAILED,REFUNDED,PARTIALLY_REFUNDED)` · `fulfillment_status VARCHAR(30) CHECK IN (UNFULFILLED,PARTIALLY_FULFILLED,FULFILLED,RETURNED)` · `currency CHAR(3)` · `subtotal/discount_total/tax_total/shipping_total/cod_fee/grand_total NUMERIC(12,2)` · `coupon_code VARCHAR(40)` · `shipping_address JSONB NOT NULL` (frozen snapshot) · `billing_address JSONB` · `shipping_method_id FK` · `shipping_method_name_ar/en` · `customer_note TEXT` · `admin_note TEXT` · `locale VARCHAR(5)` · `ip INET` · `user_agent TEXT` · `placed_at` · `paid_at` · `shipped_at` · `delivered_at` · `cancelled_at` · `cancel_reason TEXT` · `version` · timestamps
`CHECK (user_id IS NOT NULL OR guest_email IS NOT NULL)`.
Indexes: `idx_orders_user_created DESC`, `idx_orders_status_created`, `idx_orders_number`, `idx_orders_payment_status`.

**order_items** - `id` · `order_id FK ON DELETE CASCADE` · `product_id FK` · `variant_id FK NULL` · `sku` · `name_ar/name_en` · `variant_label_ar/en` · `image_url` · `unit_price NUMERIC(12,2)` · `quantity INT` · `discount_amount NUMERIC(12,2) DEFAULT 0` · `tax_amount NUMERIC(12,2) DEFAULT 0` · `line_total NUMERIC(12,2)` · `fulfilled_quantity INT DEFAULT 0` · `refunded_quantity INT DEFAULT 0`
All descriptive fields are **frozen snapshots** - catalog edits must never alter historical orders.

**order_status_history** - `id` · `order_id FK` · `from_status` · `to_status` · `note` · `changed_by FK users NULL` · `changed_by_type VARCHAR(20) CHECK IN (CUSTOMER,ADMIN,SYSTEM,WEBHOOK)` · `created_at`.

**shipments** - `id` · `order_id FK` · `carrier VARCHAR(60)` · `tracking_number VARCHAR(80)` · `tracking_url TEXT` · `status VARCHAR(30) CHECK IN (PENDING,IN_TRANSIT,OUT_FOR_DELIVERY,DELIVERED,FAILED,RETURNED)` · `cost NUMERIC(12,2)` · `shipped_at` · `delivered_at` · timestamps.
**shipment_items** - `id` · `shipment_id FK` · `order_item_id FK` · `quantity INT`.

**returns** - `id` · `order_id FK` · `user_id FK` · `reason VARCHAR(40)` · `status VARCHAR(20) CHECK IN (REQUESTED,APPROVED,REJECTED,RECEIVED,REFUNDED)` · `note TEXT` · `resolved_by` · `resolved_at` · timestamps.
**return_items** - `id` · `return_id FK` · `order_item_id FK` · `quantity INT` · `condition VARCHAR(20)`.

---

## V7 - Payments

**payments** - `id` · `order_id FK` · `provider VARCHAR(20) CHECK IN (PAYMOB,FAWRY,COD)` · `method VARCHAR(20) CHECK IN (CARD,WALLET,INSTALLMENT,KIOSK,COD)` · `provider_payment_id VARCHAR(120)` · `provider_order_id VARCHAR(120)` · `reference_code VARCHAR(60)` (Fawry) · `amount NUMERIC(12,2)` · `currency CHAR(3)` · `status VARCHAR(20) CHECK IN (PENDING,AUTHORIZED,CAPTURED,FAILED,CANCELLED,EXPIRED,REFUNDED,PARTIALLY_REFUNDED)` · `card_last4 CHAR(4)` · `card_brand VARCHAR(20)` · `idempotency_key VARCHAR(80) UNIQUE` · `failure_code VARCHAR(60)` · `failure_reason TEXT` · `raw_response JSONB` (redacted) · `expires_at` · `authorized_at` · `captured_at` · `version` · timestamps.
**Never store PAN, CVV, or expiry.**

**refunds** - `id` · `payment_id FK` · `order_id FK` · `amount NUMERIC(12,2)` · `reason VARCHAR(60)` · `note TEXT` · `provider_refund_id VARCHAR(120)` · `status VARCHAR(20) CHECK IN (PENDING,COMPLETED,FAILED)` · `created_by FK users` · `completed_at` · timestamps.

**payment_webhook_events** - `id` · `provider VARCHAR(20)` · `event_id VARCHAR(160)` · `event_type VARCHAR(60)` · `payload JSONB` · `signature TEXT` · `signature_valid BOOL` · `processed BOOL DEFAULT false` · `processed_at` · `error TEXT` · `attempts SMALLINT DEFAULT 0` · `received_at`
UNIQUE(`provider`,`event_id`) - **this is the idempotency guard.**

---

## V8 - Reviews, notifications, ops

**reviews** - `id` · `product_id FK` · `user_id FK` · `order_item_id FK NULL` · `rating SMALLINT CHECK BETWEEN 1 AND 5` · `title VARCHAR(120)` · `body TEXT` · `status VARCHAR(20) CHECK IN (PENDING,APPROVED,REJECTED) DEFAULT 'PENDING'` · `is_verified_purchase BOOL DEFAULT false` · `helpful_count INT DEFAULT 0` · `admin_reply TEXT` · `admin_replied_at` · `rejection_reason` · timestamps · UNIQUE(`product_id`,`user_id`).
**review_media** - `id` · `review_id FK` · `url` · `position`.
**review_votes** - (`review_id`,`user_id`) PK · `is_helpful BOOL`.

**notifications** - `id` · `user_id FK` · `type VARCHAR(40)` · `title_ar/en` · `body_ar/en` · `data JSONB` · `channel VARCHAR(20) CHECK IN (IN_APP,EMAIL,PUSH,SMS)` · `read_at` · `created_at`.
**device_tokens** - `id` · `user_id FK` · `token TEXT UNIQUE` · `platform VARCHAR(10) CHECK IN (IOS,ANDROID,WEB)` · `locale` · `is_active` · `last_seen_at` · `created_at`.

**audit_logs** - `id` · `actor_id UUID NULL` · `actor_type VARCHAR(20) CHECK IN (USER,ADMIN,SYSTEM)` · `actor_email` · `action VARCHAR(60)` · `entity_type VARCHAR(60)` · `entity_id UUID` · `before JSONB` · `after JSONB` · `ip INET` · `user_agent TEXT` · `request_id VARCHAR(64)` · `created_at`
Index `(entity_type, entity_id, created_at DESC)`, `(actor_id, created_at DESC)`. Append-only.

**outbox_events** - `id` · `aggregate_type VARCHAR(40)` · `aggregate_id UUID` · `event_type VARCHAR(60)` · `payload JSONB` · `published_at TIMESTAMPTZ NULL` · `attempts SMALLINT DEFAULT 0` · `last_error TEXT` · `created_at`
Index `(published_at NULLS FIRST, created_at) WHERE published_at IS NULL`.

**idempotency_keys** - `key VARCHAR(80) PK` · `user_id UUID` · `endpoint VARCHAR(120)` · `request_hash CHAR(64)` · `response_status SMALLINT` · `response_body JSONB` · `state VARCHAR(20) CHECK IN (IN_PROGRESS,COMPLETED)` · `created_at` · `expires_at`.

**settings** - `key VARCHAR(80) PK` · `value JSONB NOT NULL` · `description` · `is_public BOOL DEFAULT false` · `updated_by` · `updated_at`.

**search_queries** - `id` · `query TEXT` · `locale` · `user_id NULL` · `results_count INT` · `clicked_product_id NULL` · `created_at` - powers "popular searches" and relevance tuning.

---

## Migration file plan
```
V1__extensions_and_identity.sql
V2__catalog.sql
V3__inventory.sql
V4__cart_wishlist.sql
V5__promotions.sql
V6__orders_fulfilment.sql
V7__payments.sql
V8__reviews_notifications_ops.sql
V9__functions_and_triggers.sql     -- updated_at trigger, order_number sequence, rating rollup
R__seed_reference_data.sql         -- repeatable: roles, permissions, settings, shipping zones (EG governorates)
```
Demo/dev data lives in `backend/src/main/resources/db/seed/dev-seed.sql`, applied only under the `dev` profile.
