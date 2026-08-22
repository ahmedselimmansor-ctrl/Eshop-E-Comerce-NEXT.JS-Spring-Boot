-- ============================================================================
-- R__ (repeatable) - Reference data: permissions, roles, settings, shipping.
-- Idempotent by design: every insert is keyed on a natural key with ON CONFLICT.
-- Safe to re-run on every deploy.
-- ============================================================================

-- ------------------------------------------------------------ permissions ---
INSERT INTO permissions (id, name, resource, action, description_ar, description_en) VALUES
    (gen_random_uuid(), 'product:read',     'product',   'read',     'عرض المنتجات',              'View products'),
    (gen_random_uuid(), 'product:write',    'product',   'write',    'إضافة وتعديل المنتجات',      'Create and edit products'),
    (gen_random_uuid(), 'product:delete',   'product',   'delete',   'حذف المنتجات',               'Delete products'),
    (gen_random_uuid(), 'category:read',    'category',  'read',     'عرض الأقسام',                'View categories'),
    (gen_random_uuid(), 'category:write',   'category',  'write',    'إدارة الأقسام',              'Manage categories'),
    (gen_random_uuid(), 'brand:read',       'brand',     'read',     'عرض الماركات',               'View brands'),
    (gen_random_uuid(), 'brand:write',      'brand',     'write',    'إدارة الماركات',             'Manage brands'),
    (gen_random_uuid(), 'attribute:write',  'attribute', 'write',    'إدارة الخصائص',              'Manage attributes'),
    (gen_random_uuid(), 'inventory:read',   'inventory', 'read',     'عرض المخزون',                'View inventory'),
    (gen_random_uuid(), 'inventory:write',  'inventory', 'write',    'تعديل المخزون',              'Adjust inventory'),
    (gen_random_uuid(), 'order:read',       'order',     'read',     'عرض الطلبات',                'View orders'),
    (gen_random_uuid(), 'order:write',      'order',     'write',    'تعديل الطلبات',              'Edit orders'),
    (gen_random_uuid(), 'order:cancel',     'order',     'cancel',   'إلغاء الطلبات',              'Cancel orders'),
    (gen_random_uuid(), 'order:refund',     'order',     'refund',   'استرداد المدفوعات',          'Issue refunds'),
    (gen_random_uuid(), 'order:fulfill',    'order',     'fulfill',  'شحن الطلبات',                'Fulfil and ship orders'),
    (gen_random_uuid(), 'customer:read',    'customer',  'read',     'عرض العملاء',                'View customers'),
    (gen_random_uuid(), 'customer:write',   'customer',  'write',    'تعديل بيانات العملاء',       'Edit customers'),
    (gen_random_uuid(), 'customer:suspend', 'customer',  'suspend',  'إيقاف حسابات العملاء',       'Suspend customer accounts'),
    (gen_random_uuid(), 'coupon:read',      'coupon',    'read',     'عرض الكوبونات',              'View coupons'),
    (gen_random_uuid(), 'coupon:write',     'coupon',    'write',    'إدارة الكوبونات',            'Manage coupons'),
    (gen_random_uuid(), 'promotion:write',  'promotion', 'write',    'إدارة العروض',               'Manage promotions'),
    (gen_random_uuid(), 'review:moderate',  'review',    'moderate', 'مراجعة تقييمات المنتجات',    'Moderate product reviews'),
    (gen_random_uuid(), 'shipping:write',   'shipping',  'write',    'إدارة الشحن',                'Manage shipping'),
    (gen_random_uuid(), 'media:write',      'media',     'write',    'رفع وحذف الوسائط',           'Upload and delete media'),
    (gen_random_uuid(), 'report:read',      'report',    'read',     'عرض التقارير',               'View reports'),
    (gen_random_uuid(), 'user:read',        'user',      'read',     'عرض المستخدمين',             'View admin users'),
    (gen_random_uuid(), 'user:write',       'user',      'write',    'إدارة المستخدمين',           'Manage admin users'),
    (gen_random_uuid(), 'role:write',       'role',      'write',    'إدارة الأدوار والصلاحيات',   'Manage roles and permissions'),
    (gen_random_uuid(), 'settings:write',   'settings',  'write',    'تعديل إعدادات المتجر',       'Edit store settings'),
    (gen_random_uuid(), 'audit:read',       'audit',     'read',     'عرض سجل التدقيق',            'View audit log')
ON CONFLICT (name) DO UPDATE
    SET description_ar = EXCLUDED.description_ar,
        description_en = EXCLUDED.description_en;

-- ------------------------------------------------------------------ roles ---
INSERT INTO roles (id, name, description_ar, description_en, is_system) VALUES
    (gen_random_uuid(), 'CUSTOMER',        'عميل',                    'Customer',        TRUE),
    (gen_random_uuid(), 'SUPPORT',         'خدمة عملاء',              'Support agent',   TRUE),
    (gen_random_uuid(), 'CATALOG_MANAGER', 'مسؤول الكتالوج',          'Catalog manager', TRUE),
    (gen_random_uuid(), 'ORDER_MANAGER',   'مسؤول الطلبات',           'Order manager',   TRUE),
    (gen_random_uuid(), 'ADMIN',           'مدير',                    'Administrator',   TRUE),
    (gen_random_uuid(), 'SUPER_ADMIN',     'مدير عام',                'Super admin',     TRUE)
ON CONFLICT (name) DO UPDATE
    SET description_ar = EXCLUDED.description_ar,
        description_en = EXCLUDED.description_en;

-- ------------------------------------------------------- role → permission --
-- Rebuilt from scratch each run so the grant matrix below is authoritative.
DELETE FROM role_permissions
 WHERE role_id IN (SELECT id FROM roles WHERE is_system);

-- SUPPORT
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
 WHERE r.name = 'SUPPORT'
   AND p.name IN ('order:read', 'customer:read', 'review:moderate', 'product:read', 'inventory:read')
ON CONFLICT DO NOTHING;

-- CATALOG_MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
 WHERE r.name = 'CATALOG_MANAGER'
   AND (p.resource IN ('product', 'category', 'brand', 'attribute', 'inventory', 'media')
        OR p.name = 'report:read')
ON CONFLICT DO NOTHING;

-- ORDER_MANAGER
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
 WHERE r.name = 'ORDER_MANAGER'
   AND (p.resource = 'order' OR p.name IN ('customer:read', 'report:read', 'shipping:write', 'inventory:read'))
ON CONFLICT DO NOTHING;

-- ADMIN - everything except role and settings management
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
 WHERE r.name = 'ADMIN'
   AND p.name NOT IN ('role:write', 'settings:write')
ON CONFLICT DO NOTHING;

-- SUPER_ADMIN - everything
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
 WHERE r.name = 'SUPER_ADMIN'
ON CONFLICT DO NOTHING;

-- ------------------------------------------------------------- warehouse ----
INSERT INTO warehouses (id, code, name_ar, name_en, governorate, city, is_active, priority)
VALUES (gen_random_uuid(), 'MAIN', 'المخزن الرئيسي', 'Main warehouse', 'القاهرة', 'القاهرة', TRUE, 0)
ON CONFLICT (code) DO UPDATE SET name_ar = EXCLUDED.name_ar, name_en = EXCLUDED.name_en;

-- -------------------------------------------------------- shipping zones ----
INSERT INTO shipping_zones (id, name_ar, name_en, governorates, is_active)
SELECT gen_random_uuid(), z.name_ar, z.name_en, z.govs::jsonb, TRUE
  FROM (VALUES
    ('القاهرة الكبرى', 'Greater Cairo',
     '["القاهرة","الجيزة","القليوبية"]'),
    ('الإسكندرية والدلتا', 'Alexandria & Delta',
     '["الإسكندرية","البحيرة","كفر الشيخ","الغربية","المنوفية","الدقهلية","دمياط","الشرقية"]'),
    ('القناة وسيناء', 'Canal & Sinai',
     '["بورسعيد","الإسماعيلية","السويس","شمال سيناء","جنوب سيناء"]'),
    ('الصعيد', 'Upper Egypt',
     '["الفيوم","بني سويف","المنيا","أسيوط","سوهاج","قنا","الأقصر","أسوان"]'),
    ('المناطق النائية', 'Remote areas',
     '["البحر الأحمر","الوادي الجديد","مطروح"]')
  ) AS z(name_ar, name_en, govs)
 WHERE NOT EXISTS (SELECT 1 FROM shipping_zones sz WHERE sz.name_ar = z.name_ar);

-- ------------------------------------------------------ shipping methods ----
INSERT INTO shipping_methods (id, code, name_ar, name_en, description_ar, description_en, zone_id,
                              base_cost, cost_per_kg, free_shipping_threshold,
                              estimated_days_min, estimated_days_max, supports_cod, is_active, position)
SELECT gen_random_uuid(), m.code, m.name_ar, m.name_en, m.desc_ar, m.desc_en,
       (SELECT id FROM shipping_zones WHERE name_ar = m.zone_ar),
       m.base_cost, m.per_kg, m.free_threshold, m.dmin, m.dmax, m.cod, TRUE, m.pos
  FROM (VALUES
    ('CAIRO_STD',   'توصيل عادي - القاهرة الكبرى', 'Standard - Greater Cairo',
     'التوصيل خلال ١-٢ يوم عمل', 'Delivered within 1-2 business days',
     'القاهرة الكبرى',       40.00, 0.00, 1000.00, 1, 2, TRUE, 1),
    ('CAIRO_EXP',   'توصيل سريع - نفس اليوم',      'Express - same day',
     'الطلبات قبل الساعة ١٢ ظهراً تصل في نفس اليوم', 'Order before 12 PM for same-day delivery',
     'القاهرة الكبرى',       90.00, 0.00, NULL,    0, 1, FALSE, 2),
    ('DELTA_STD',   'توصيل عادي - الإسكندرية والدلتا', 'Standard - Alexandria & Delta',
     'التوصيل خلال ٢-٣ أيام عمل', 'Delivered within 2-3 business days',
     'الإسكندرية والدلتا',   55.00, 5.00, 1500.00, 2, 3, TRUE, 3),
    ('CANAL_STD',   'توصيل عادي - القناة وسيناء',  'Standard - Canal & Sinai',
     'التوصيل خلال ٣-٤ أيام عمل', 'Delivered within 3-4 business days',
     'القناة وسيناء',        65.00, 5.00, 1500.00, 3, 4, TRUE, 4),
    ('SAID_STD',    'توصيل عادي - الصعيد',          'Standard - Upper Egypt',
     'التوصيل خلال ٣-٥ أيام عمل', 'Delivered within 3-5 business days',
     'الصعيد',               70.00, 6.00, 2000.00, 3, 5, TRUE, 5),
    ('REMOTE_STD',  'توصيل - المناطق النائية',      'Remote areas',
     'التوصيل خلال ٥-٧ أيام عمل', 'Delivered within 5-7 business days',
     'المناطق النائية',      110.00, 8.00, NULL,   5, 7, FALSE, 6)
  ) AS m(code, name_ar, name_en, desc_ar, desc_en, zone_ar,
         base_cost, per_kg, free_threshold, dmin, dmax, cod, pos)
ON CONFLICT (code) DO UPDATE
    SET name_ar = EXCLUDED.name_ar, name_en = EXCLUDED.name_en,
        base_cost = EXCLUDED.base_cost, cost_per_kg = EXCLUDED.cost_per_kg,
        free_shipping_threshold = EXCLUDED.free_shipping_threshold;

-- -------------------------------------------------------------- settings ----
INSERT INTO settings (key, value, description, is_public) VALUES
    ('store.name',              '{"ar":"إي‑شوب","en":"Eshop"}',        'Store display name',             TRUE),
    ('store.email',             '"support@eshop.eg"',                   'Public support email',           TRUE),
    ('store.phone',             '"+20 100 000 0000"',                   'Public support phone',           TRUE),
    ('store.currency',          '"EGP"',                                'Default currency',               TRUE),
    ('store.locales',           '["ar","en"]',                          'Enabled locales',                TRUE),
    ('store.social',            '{"facebook":"","instagram":"","x":"","tiktok":""}', 'Social links',      TRUE),
    ('checkout.cod_fee',        '20.00',                                'Cash-on-delivery surcharge',     TRUE),
    ('checkout.tax_rate',       '0.14',                                 'Default VAT rate',               TRUE),
    ('checkout.min_order',      '50.00',                                'Minimum order amount',           TRUE),
    ('checkout.guest_enabled',  'true',                                 'Allow guest checkout',           TRUE),
    ('catalog.reviews_require_purchase', 'false',                       'Only buyers may review',         FALSE),
    ('catalog.reviews_auto_approve',     'false',                       'Auto-approve new reviews',       FALSE),
    ('inventory.allow_backorder',        'false',                       'Allow ordering out-of-stock',    FALSE),
    ('inventory.low_stock_threshold',    '5',                           'Low stock alert threshold',      FALSE),
    ('order.auto_cancel_minutes',        '30',                          'Unpaid order auto-cancel window', FALSE),
    ('order.return_window_days',         '14',                          'Return request window',          TRUE)
ON CONFLICT (key) DO NOTHING;
