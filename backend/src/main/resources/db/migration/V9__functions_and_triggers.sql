-- ============================================================================
-- V9 - Functions, triggers, and generated identifiers
-- ============================================================================

-- ----------------------------------------------------------- updated_at -----
CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DO $$
DECLARE
    t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'users', 'roles', 'addresses', 'oauth_accounts',
        'categories', 'brands', 'attributes', 'products', 'product_variants',
        'warehouses', 'inventory_items',
        'carts', 'cart_items', 'wishlists',
        'coupons', 'promotions',
        'shipping_zones', 'shipping_methods', 'orders', 'shipments', 'returns',
        'payments', 'refunds',
        'reviews'
    ] LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_%1$s_updated_at BEFORE UPDATE ON %1$s
             FOR EACH ROW EXECUTE FUNCTION touch_updated_at()', t);
    END LOOP;
END;
$$;

-- --------------------------------------------------------- order numbers ----
-- Human-facing identifier: EG-YYMMDD-NNNNN. The daily sequence is shared across
-- the cluster, so it stays collision-free under concurrent checkouts.
CREATE SEQUENCE order_number_seq START 1;

CREATE OR REPLACE FUNCTION next_order_number() RETURNS VARCHAR AS $$
DECLARE
    seq_part BIGINT;
BEGIN
    seq_part := nextval('order_number_seq');
    RETURN 'EG-' || to_char(now() AT TIME ZONE 'UTC', 'YYMMDD') || '-' || lpad((seq_part % 100000)::TEXT, 5, '0');
END;
$$ LANGUAGE plpgsql;

CREATE SEQUENCE return_number_seq START 1;

CREATE OR REPLACE FUNCTION next_return_number() RETURNS VARCHAR AS $$
BEGIN
    RETURN 'RT-' || to_char(now() AT TIME ZONE 'UTC', 'YYMMDD') || '-' ||
           lpad((nextval('return_number_seq') % 100000)::TEXT, 5, '0');
END;
$$ LANGUAGE plpgsql;

-- ------------------------------------------------------ category path -------
-- Keeps `path` and `level` consistent so descendant lookups stay a prefix scan.
CREATE OR REPLACE FUNCTION rebuild_category_path() RETURNS TRIGGER AS $$
DECLARE
    parent_path  TEXT;
    parent_level SMALLINT;
BEGIN
    IF NEW.parent_id IS NULL THEN
        NEW.path  := '/' || NEW.slug;
        NEW.level := 0;
    ELSE
        SELECT path, level INTO parent_path, parent_level FROM categories WHERE id = NEW.parent_id;
        IF parent_path IS NULL THEN
            RAISE EXCEPTION 'Parent category % not found', NEW.parent_id;
        END IF;

        -- Cycle guard.
        --
        -- Compares the prospective parent's path against this row's *existing* path: a node may
        -- only move somewhere that is not inside its own subtree. Matching on the slug instead
        -- (the obvious first attempt) fails as soon as two branches share a segment name, and says
        -- nothing at all about ancestry.
        IF TG_OP = 'UPDATE' AND OLD.path <> '' THEN
            IF NEW.parent_id = OLD.id OR parent_path = OLD.path
               OR parent_path LIKE OLD.path || '/%' THEN
                RAISE EXCEPTION
                    'Cycle detected: cannot move category % beneath its own descendant', OLD.slug;
            END IF;
        END IF;

        NEW.path  := parent_path || '/' || NEW.slug;
        NEW.level := parent_level + 1;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Fires on any insert or update, not only when parent_id or slug appear in the SET list. An
-- UPDATE that touches neither still needs `path` to stay consistent, and restricting the trigger
-- to named columns silently skips those cases.
CREATE TRIGGER trg_categories_path
    BEFORE INSERT OR UPDATE ON categories
    FOR EACH ROW EXECUTE FUNCTION rebuild_category_path();

-- Re-path the whole subtree when a node moves or is renamed.
CREATE OR REPLACE FUNCTION cascade_category_path() RETURNS TRIGGER AS $$
BEGIN
    UPDATE categories
       SET path  = NEW.path || substring(path FROM length(OLD.path) + 1),
           level = level + (NEW.level - OLD.level)
     WHERE path LIKE OLD.path || '/%';

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- `WHEN (NEW.path IS DISTINCT FROM OLD.path)`, not `AFTER UPDATE OF path`.
--
-- `UPDATE OF path` fires only when `path` is named in the statement's SET list - but `path` is
-- never set by a statement, it is computed by the BEFORE trigger above. So the column-scoped form
-- never fires, and renaming a parent leaves every descendant holding a stale path. The WHEN clause
-- tests the value that actually changed, which is what was meant.
CREATE TRIGGER trg_categories_path_cascade
    AFTER UPDATE ON categories
    FOR EACH ROW
    WHEN (NEW.path IS DISTINCT FROM OLD.path)
    EXECUTE FUNCTION cascade_category_path();

-- --------------------------------------------------- product rating rollup --
-- Denormalised rating_avg/rating_count on products, maintained from approved
-- reviews only. Avoids an aggregate on every product listing query.
CREATE OR REPLACE FUNCTION refresh_product_rating() RETURNS TRIGGER AS $$
DECLARE
    target_product UUID;
BEGIN
    target_product := COALESCE(NEW.product_id, OLD.product_id);

    UPDATE products p
       SET rating_avg   = COALESCE(agg.avg_rating, 0),
           rating_count = COALESCE(agg.cnt, 0)
      FROM (
            SELECT round(avg(rating)::numeric, 1) AS avg_rating, count(*) AS cnt
              FROM reviews
             WHERE product_id = target_product AND status = 'APPROVED'
           ) agg
     WHERE p.id = target_product;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reviews_rating_rollup
    AFTER INSERT OR UPDATE OF rating, status OR DELETE ON reviews
    FOR EACH ROW EXECUTE FUNCTION refresh_product_rating();

-- ------------------------------------------------ category product count ----
CREATE OR REPLACE FUNCTION refresh_category_product_count() RETURNS TRIGGER AS $$
DECLARE
    affected UUID;
BEGIN
    FOREACH affected IN ARRAY ARRAY[NEW.primary_category_id, OLD.primary_category_id] LOOP
        IF affected IS NOT NULL THEN
            UPDATE categories c
               SET product_count = (
                     SELECT count(*) FROM products
                      WHERE primary_category_id = affected
                        AND status = 'ACTIVE' AND deleted_at IS NULL)
             WHERE c.id = affected;
        END IF;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_products_category_count
    AFTER INSERT OR UPDATE OF primary_category_id, status, deleted_at OR DELETE ON products
    FOR EACH ROW EXECUTE FUNCTION refresh_category_product_count();

-- --------------------------------------------------- audit immutability -----
CREATE OR REPLACE FUNCTION reject_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_immutable
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER trg_inventory_movements_immutable
    BEFORE UPDATE OR DELETE ON inventory_movements
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ------------------------------------------------- housekeeping helpers -----
-- Called by the nightly maintenance job (see CleanupScheduler).
CREATE OR REPLACE FUNCTION purge_expired_rows() RETURNS TABLE (table_name TEXT, deleted BIGINT) AS $$
DECLARE
    n BIGINT;
BEGIN
    DELETE FROM refresh_tokens WHERE expires_at < now() - INTERVAL '7 days';
    GET DIAGNOSTICS n = ROW_COUNT; table_name := 'refresh_tokens'; deleted := n; RETURN NEXT;

    DELETE FROM one_time_tokens WHERE expires_at < now() - INTERVAL '1 day';
    GET DIAGNOSTICS n = ROW_COUNT; table_name := 'one_time_tokens'; deleted := n; RETURN NEXT;

    DELETE FROM idempotency_keys WHERE expires_at < now();
    GET DIAGNOSTICS n = ROW_COUNT; table_name := 'idempotency_keys'; deleted := n; RETURN NEXT;

    DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < now() - INTERVAL '30 days';
    GET DIAGNOSTICS n = ROW_COUNT; table_name := 'outbox_events'; deleted := n; RETURN NEXT;

    UPDATE carts SET status = 'ABANDONED'
     WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at < now();
    GET DIAGNOSTICS n = ROW_COUNT; table_name := 'carts_abandoned'; deleted := n; RETURN NEXT;

    RETURN;
END;
$$ LANGUAGE plpgsql;
