-- Demo catalog for local development.
--
-- Deliberately NOT a Flyway migration. Migrations run everywhere, including production, and a real
-- store does not want eight fictional products in its catalog. This is applied only under the `dev`
-- and `local` profiles, by DevSeedLoader.
--
-- Idempotent: every statement ends in an untargeted ON CONFLICT DO NOTHING, so running it twice
-- changes nothing and starting an already-seeded environment is not an error. The conflict target is
-- omitted on purpose - products' unique indexes on sku and slug are partial (WHERE deleted_at IS
-- NULL) and a partial index cannot be named as an ON CONFLICT target without repeating its predicate.
--
-- Prices are in EGP and roughly plausible for the Egyptian market, because a catalog full of
-- 9.99 placeholders makes it impossible to notice when currency formatting or the COD fee is wrong.

-- ---------------------------------------------------------------------------- brands --
INSERT INTO brands (id, slug, name_ar, name_en, is_active) VALUES
    ('0192a000-0000-7000-8000-00000000b001', 'samsung',  'سامسونج',   'Samsung',  TRUE),
    ('0192a000-0000-7000-8000-00000000b002', 'apple',    'أبل',       'Apple',    TRUE),
    ('0192a000-0000-7000-8000-00000000b003', 'anker',    'أنكر',      'Anker',    TRUE),
    ('0192a000-0000-7000-8000-00000000b004', 'zara',     'زارا',      'Zara',     TRUE),
    ('0192a000-0000-7000-8000-00000000b005', 'tefal',    'تيفال',     'Tefal',    TRUE)
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------------------ categories --
-- Two levels: the tree component and the breadcrumb are both wrong in ways you only see with a
-- parent and a child present.
INSERT INTO categories (id, parent_id, slug, name_ar, name_en, position, is_active) VALUES
    ('0192a000-0000-7000-8000-00000000c001', NULL, 'electronics', 'إلكترونيات', 'Electronics', 1, TRUE),
    ('0192a000-0000-7000-8000-00000000c002', NULL, 'fashion',     'أزياء',      'Fashion',     2, TRUE),
    ('0192a000-0000-7000-8000-00000000c003', NULL, 'home',        'المنزل',     'Home',        3, TRUE)
ON CONFLICT DO NOTHING;

INSERT INTO categories (id, parent_id, slug, name_ar, name_en, position, is_active) VALUES
    ('0192a000-0000-7000-8000-00000000c101', '0192a000-0000-7000-8000-00000000c001',
     'mobile-phones', 'هواتف محمولة', 'Mobile Phones', 1, TRUE),
    ('0192a000-0000-7000-8000-00000000c102', '0192a000-0000-7000-8000-00000000c001',
     'accessories',   'إكسسوارات',    'Accessories',   2, TRUE),
    ('0192a000-0000-7000-8000-00000000c201', '0192a000-0000-7000-8000-00000000c002',
     'mens-clothing', 'ملابس رجالي',  'Men''s Clothing', 1, TRUE),
    ('0192a000-0000-7000-8000-00000000c301', '0192a000-0000-7000-8000-00000000c003',
     'kitchen',       'مطبخ',         'Kitchen',       1, TRUE)
ON CONFLICT DO NOTHING;

-- -------------------------------------------------------------------------- products --
-- One of each interesting case: on sale, featured, plain, and one DRAFT so the admin catalog has
-- something the storefront must not show.
INSERT INTO products (
    id, sku, slug, name_ar, name_en,
    short_description_ar, short_description_en,
    description_ar, description_en,
    brand_id, primary_category_id,
    status, base_price, compare_at_price, cost_price, currency,
    is_featured, published_at
) VALUES
    ('0192a000-0000-7000-8000-00000000d001', 'SAM-S24-256', 'samsung-galaxy-s24-256gb',
     'سامسونج جالاكسي S24 - 256 جيجابايت', 'Samsung Galaxy S24 - 256GB',
     'شاشة 6.2 بوصة، كاميرا 50 ميجابكسل', '6.2-inch display, 50MP camera',
     'هاتف رائد بشاشة AMOLED وبطارية تدوم طوال اليوم.',
     'A flagship phone with an AMOLED display and all-day battery.',
     '0192a000-0000-7000-8000-00000000b001', '0192a000-0000-7000-8000-00000000c101',
     'ACTIVE', 32999.00, 36999.00, 27000.00, 'EGP', TRUE, now()),

    ('0192a000-0000-7000-8000-00000000d002', 'APL-IP15-128', 'apple-iphone-15-128gb',
     'أبل آيفون 15 - 128 جيجابايت', 'Apple iPhone 15 - 128GB',
     'شريحة A16 بيونيك', 'A16 Bionic chip',
     'آيفون 15 بمنفذ USB-C وكاميرا 48 ميجابكسل.',
     'iPhone 15 with USB-C and a 48MP main camera.',
     '0192a000-0000-7000-8000-00000000b002', '0192a000-0000-7000-8000-00000000c101',
     'ACTIVE', 44999.00, NULL, 39000.00, 'EGP', TRUE, now()),

    ('0192a000-0000-7000-8000-00000000d003', 'ANK-PB-20K', 'anker-powercore-20000',
     'أنكر باور كور 20000', 'Anker PowerCore 20000',
     'بطارية محمولة 20000 مللي أمبير', '20,000mAh portable charger',
     'بطارية محمولة تشحن الهاتف أربع مرات.',
     'A portable charger that refills a phone four times over.',
     '0192a000-0000-7000-8000-00000000b003', '0192a000-0000-7000-8000-00000000c102',
     'ACTIVE', 1899.00, 2400.00, 1200.00, 'EGP', FALSE, now()),

    ('0192a000-0000-7000-8000-00000000d004', 'ANK-CBL-USBC', 'anker-usb-c-cable-2m',
     'كابل أنكر USB-C - 2 متر', 'Anker USB-C Cable - 2m',
     'كابل مضفر متين', 'Braided, 2 metres',
     'كابل شحن سريع مضفر بطول مترين.',
     'A braided two-metre fast-charging cable.',
     '0192a000-0000-7000-8000-00000000b003', '0192a000-0000-7000-8000-00000000c102',
     'ACTIVE', 349.00, NULL, 180.00, 'EGP', FALSE, now()),

    ('0192a000-0000-7000-8000-00000000d005', 'ZRA-SHRT-OX', 'zara-oxford-shirt',
     'قميص أكسفورد', 'Oxford Shirt',
     'قطن 100%', '100% cotton',
     'قميص أكسفورد قطني بقصة كلاسيكية.',
     'A classic-fit cotton Oxford shirt.',
     '0192a000-0000-7000-8000-00000000b004', '0192a000-0000-7000-8000-00000000c201',
     'ACTIVE', 1250.00, 1650.00, 600.00, 'EGP', FALSE, now()),

    ('0192a000-0000-7000-8000-00000000d006', 'TFL-PAN-28', 'tefal-frying-pan-28cm',
     'مقلاة تيفال 28 سم', 'Tefal Frying Pan 28cm',
     'طبقة مانعة للالتصاق', 'Non-stick coating',
     'مقلاة مانعة للالتصاق بمؤشر حرارة.',
     'A non-stick pan with a heat indicator.',
     '0192a000-0000-7000-8000-00000000b005', '0192a000-0000-7000-8000-00000000c301',
     'ACTIVE', 899.00, NULL, 500.00, 'EGP', FALSE, now()),

    ('0192a000-0000-7000-8000-00000000d007', 'TFL-KTL-17', 'tefal-kettle-1-7l',
     'غلاية تيفال 1.7 لتر', 'Tefal Kettle 1.7L',
     'غلاية كهربائية سريعة', 'Fast-boil electric kettle',
     'غلاية كهربائية بسعة 1.7 لتر وإيقاف تلقائي.',
     'A 1.7-litre electric kettle with automatic shut-off.',
     '0192a000-0000-7000-8000-00000000b005', '0192a000-0000-7000-8000-00000000c301',
     'ACTIVE', 1450.00, 1799.00, 800.00, 'EGP', FALSE, now()),

    -- Draft: visible in the admin catalog, absent from every storefront listing. If this ever shows
    -- up on the storefront, the status filter has regressed.
    ('0192a000-0000-7000-8000-00000000d008', 'SAM-S25-512', 'samsung-galaxy-s25-512gb',
     'سامسونج جالاكسي S25 - 512 جيجابايت', 'Samsung Galaxy S25 - 512GB',
     'لم يُطرح بعد', 'Not launched yet',
     'مسودة لم تُنشر بعد.', 'An unpublished draft.',
     '0192a000-0000-7000-8000-00000000b001', '0192a000-0000-7000-8000-00000000c101',
     'DRAFT', 51999.00, NULL, 44000.00, 'EGP', FALSE, NULL)
ON CONFLICT DO NOTHING;

-- Secondary category links, so a product belonging to more than one category is exercised.
INSERT INTO product_categories (product_id, category_id)
SELECT p.id, c.id
  FROM products p, categories c
 WHERE (p.sku, c.slug) IN (
        ('SAM-S24-256',  'electronics'),
        ('APL-IP15-128', 'electronics'),
        ('ANK-PB-20K',   'electronics'),
        ('TFL-PAN-28',   'home'),
        ('TFL-KTL-17',   'home'))
ON CONFLICT DO NOTHING;

-- ---------------------------------------------------------------------------- images --
-- Placeholder host, deliberately: no binaries in the repository, and a broken image is immediately
-- obvious in a way a missing one is not.
INSERT INTO product_images (id, product_id, url, alt_ar, alt_en, position, is_primary)
SELECT
    ('0192a000-0000-7000-8000-0000000' || lpad((row_number() OVER (ORDER BY p.sku))::text, 5, '0'))::uuid,
    p.id,
    'https://placehold.co/800x800/e2e8f0/1e293b?text=' || replace(p.sku, '-', '+'),
    p.name_ar, COALESCE(p.name_en, p.name_ar), 0, TRUE
  FROM products p
 WHERE p.sku LIKE ANY (ARRAY['SAM-%', 'APL-%', 'ANK-%', 'ZRA-%', 'TFL-%'])
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------------------- inventory --
-- One stock row per product in the seeded warehouse. SAM-S25-512 is left at zero so the out-of-stock
-- path has something to render, and ZRA-SHRT-OX sits below its reorder level to populate the
-- low-stock panel on the dashboard.
INSERT INTO inventory_items (id, product_id, warehouse_id, quantity_on_hand, quantity_reserved, reorder_level)
SELECT
    ('0192a000-0000-7000-8000-0000000' || lpad((1000 + row_number() OVER (ORDER BY p.sku))::text, 5, '0'))::uuid,
    p.id,
    w.id,
    CASE p.sku WHEN 'SAM-S25-512' THEN 0 WHEN 'ZRA-SHRT-OX' THEN 3 ELSE 40 END,
    0,
    CASE p.sku WHEN 'ZRA-SHRT-OX' THEN 10 ELSE 5 END
  FROM products p
 CROSS JOIN (SELECT id FROM warehouses ORDER BY priority, code LIMIT 1) w
 WHERE p.sku LIKE ANY (ARRAY['SAM-%', 'APL-%', 'ANK-%', 'ZRA-%', 'TFL-%'])
ON CONFLICT DO NOTHING;
