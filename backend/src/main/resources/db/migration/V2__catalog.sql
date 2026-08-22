-- ============================================================================
-- V2 — Catalog: categories, brands, attributes, products, variants, images
-- ============================================================================

CREATE TABLE categories (
    id                    UUID PRIMARY KEY,
    parent_id             UUID REFERENCES categories (id) ON DELETE RESTRICT,
    slug                  VARCHAR(140) NOT NULL UNIQUE,
    name_ar               VARCHAR(160) NOT NULL,
    name_en               VARCHAR(160),
    description_ar        TEXT,
    description_en        TEXT,
    image_url             TEXT,
    icon                  VARCHAR(60),
    position              INT          NOT NULL DEFAULT 0,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    path                  TEXT         NOT NULL DEFAULT '',
    level                 SMALLINT     NOT NULL DEFAULT 0,
    product_count         INT          NOT NULL DEFAULT 0,
    seo_title_ar          VARCHAR(200),
    seo_title_en          VARCHAR(200),
    seo_description_ar    VARCHAR(400),
    seo_description_en    VARCHAR(400),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_categories_not_self_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
CREATE INDEX idx_categories_parent_position ON categories (parent_id, position);
CREATE INDEX idx_categories_path ON categories (path text_pattern_ops);
CREATE INDEX idx_categories_active ON categories (is_active) WHERE is_active;

COMMENT ON COLUMN categories.path IS 'Materialised ancestry, e.g. /electronics/phones/smartphones — enables descendant queries with a LIKE prefix.';

CREATE TABLE brands (
    id              UUID PRIMARY KEY,
    slug            VARCHAR(140) NOT NULL UNIQUE,
    name_ar         VARCHAR(160) NOT NULL,
    name_en         VARCHAR(160),
    logo_url        TEXT,
    description_ar  TEXT,
    description_en  TEXT,
    website         VARCHAR(255),
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_featured     BOOLEAN      NOT NULL DEFAULT FALSE,
    position        INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_brands_active_position ON brands (is_active, position);

-- ------------------------------------------------------------ attributes ----
CREATE TABLE attributes (
    id                UUID PRIMARY KEY,
    code              VARCHAR(50)  NOT NULL UNIQUE,
    name_ar           VARCHAR(120) NOT NULL,
    name_en           VARCHAR(120),
    type              VARCHAR(20)  NOT NULL,
    is_variant_axis   BOOLEAN      NOT NULL DEFAULT FALSE,
    is_filterable     BOOLEAN      NOT NULL DEFAULT TRUE,
    is_searchable     BOOLEAN      NOT NULL DEFAULT FALSE,
    is_required       BOOLEAN      NOT NULL DEFAULT FALSE,
    unit              VARCHAR(20),
    position          INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_attributes_type CHECK (type IN ('TEXT', 'NUMBER', 'BOOLEAN', 'SELECT', 'MULTISELECT', 'COLOR'))
);

CREATE TABLE attribute_values (
    id             UUID PRIMARY KEY,
    attribute_id   UUID         NOT NULL REFERENCES attributes (id) ON DELETE CASCADE,
    code           VARCHAR(60)  NOT NULL,
    value_ar       VARCHAR(160) NOT NULL,
    value_en       VARCHAR(160),
    hex_color      VARCHAR(7),
    image_url      TEXT,
    position       INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_attribute_values UNIQUE (attribute_id, code)
);
CREATE INDEX idx_attribute_values_attribute ON attribute_values (attribute_id, position);

-- -------------------------------------------------------------- products ----
CREATE TABLE products (
    id                    UUID PRIMARY KEY,
    sku                   VARCHAR(64)  NOT NULL,
    slug                  VARCHAR(180) NOT NULL,
    name_ar               VARCHAR(255) NOT NULL,
    name_en               VARCHAR(255),
    short_description_ar  VARCHAR(600),
    short_description_en  VARCHAR(600),
    description_ar        TEXT,
    description_en        TEXT,
    brand_id              UUID REFERENCES brands (id) ON DELETE SET NULL,
    primary_category_id   UUID REFERENCES categories (id) ON DELETE SET NULL,
    status                VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    type                  VARCHAR(20)   NOT NULL DEFAULT 'SIMPLE',
    base_price            NUMERIC(12,2) NOT NULL,
    compare_at_price      NUMERIC(12,2),
    cost_price            NUMERIC(12,2),
    currency              VARCHAR(3)    NOT NULL DEFAULT 'EGP',
    tax_class             VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',
    tax_rate              NUMERIC(5,4)  NOT NULL DEFAULT 0.1400,
    weight_grams          INT,
    length_mm             INT,
    width_mm              INT,
    height_mm             INT,
    is_featured           BOOLEAN       NOT NULL DEFAULT FALSE,
    is_digital            BOOLEAN       NOT NULL DEFAULT FALSE,
    requires_shipping     BOOLEAN       NOT NULL DEFAULT TRUE,
    rating_avg            NUMERIC(2,1)  NOT NULL DEFAULT 0,
    rating_count          INT           NOT NULL DEFAULT 0,
    view_count            BIGINT        NOT NULL DEFAULT 0,
    sold_count            INT           NOT NULL DEFAULT 0,
    seo_title_ar          VARCHAR(200),
    seo_title_en          VARCHAR(200),
    seo_description_ar    VARCHAR(400),
    seo_description_en    VARCHAR(400),
    published_at          TIMESTAMPTZ,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT ck_products_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_products_type CHECK (type IN ('SIMPLE', 'VARIABLE')),
    CONSTRAINT ck_products_base_price CHECK (base_price >= 0),
    CONSTRAINT ck_products_compare_price CHECK (compare_at_price IS NULL OR compare_at_price >= base_price),
    CONSTRAINT ck_products_rating CHECK (rating_avg >= 0 AND rating_avg <= 5)
);
CREATE UNIQUE INDEX uq_products_sku ON products (sku) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_products_slug ON products (slug) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_status_published ON products (status, published_at DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_brand ON products (brand_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_primary_category ON products (primary_category_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_featured ON products (is_featured, published_at DESC) WHERE is_featured AND deleted_at IS NULL;
CREATE INDEX idx_products_price ON products (base_price) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_sold ON products (sold_count DESC) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_name_ar_trgm ON products USING gin (name_ar gin_trgm_ops);
CREATE INDEX idx_products_name_en_trgm ON products USING gin (name_en gin_trgm_ops);

COMMENT ON TABLE products IS 'Elasticsearch mirrors this table for search; PostgreSQL remains the source of truth.';

CREATE TABLE product_categories (
    product_id   UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    category_id  UUID NOT NULL REFERENCES categories (id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, category_id)
);
CREATE INDEX idx_product_categories_category ON product_categories (category_id);

-- -------------------------------------------------------------- variants ----
CREATE TABLE product_variants (
    id                UUID PRIMARY KEY,
    product_id        UUID          NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    sku               VARCHAR(64)   NOT NULL UNIQUE,
    barcode           VARCHAR(64),
    price             NUMERIC(12,2) NOT NULL,
    compare_at_price  NUMERIC(12,2),
    cost_price        NUMERIC(12,2),
    weight_grams      INT,
    image_url         TEXT,
    position          INT           NOT NULL DEFAULT 0,
    is_active         BOOLEAN       NOT NULL DEFAULT TRUE,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_variants_price CHECK (price >= 0)
);
CREATE INDEX idx_product_variants_product ON product_variants (product_id, position);

CREATE TABLE variant_attribute_values (
    variant_id          UUID NOT NULL REFERENCES product_variants (id) ON DELETE CASCADE,
    attribute_id        UUID NOT NULL REFERENCES attributes (id) ON DELETE RESTRICT,
    attribute_value_id  UUID NOT NULL REFERENCES attribute_values (id) ON DELETE RESTRICT,
    PRIMARY KEY (variant_id, attribute_id)
);
CREATE INDEX idx_variant_attr_values_value ON variant_attribute_values (attribute_value_id);

CREATE TABLE product_attribute_values (
    id                  UUID PRIMARY KEY,
    product_id          UUID NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    attribute_id        UUID NOT NULL REFERENCES attributes (id) ON DELETE CASCADE,
    attribute_value_id  UUID REFERENCES attribute_values (id) ON DELETE CASCADE,
    custom_value        TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_pav_has_value CHECK (attribute_value_id IS NOT NULL OR custom_value IS NOT NULL)
);
CREATE UNIQUE INDEX uq_product_attribute_values
    ON product_attribute_values (product_id, attribute_id, coalesce(attribute_value_id, '00000000-0000-0000-0000-000000000000'::uuid));
CREATE INDEX idx_pav_attribute_value ON product_attribute_values (attribute_id, attribute_value_id);

-- ---------------------------------------------------------------- images ----
CREATE TABLE product_images (
    id          UUID PRIMARY KEY,
    product_id  UUID        NOT NULL REFERENCES products (id) ON DELETE CASCADE,
    variant_id  UUID REFERENCES product_variants (id) ON DELETE CASCADE,
    url         TEXT        NOT NULL,
    object_key  TEXT,
    alt_ar      VARCHAR(255),
    alt_en      VARCHAR(255),
    position    INT         NOT NULL DEFAULT 0,
    is_primary  BOOLEAN     NOT NULL DEFAULT FALSE,
    width       INT,
    height      INT,
    blurhash    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_images_product ON product_images (product_id, position);
CREATE UNIQUE INDEX uq_product_images_primary ON product_images (product_id) WHERE is_primary;
