import { describe, expect, it } from 'vitest';
import {
  adminProductDetailSchema,
  adminProductRowSchema,
  cartSchema,
  countMetricSchema,
  dashboardSummarySchema,
  metricSchema,
  moneySchema,
  orderSchema,
  productCardSchema,
  publicSettingsSchema,
  settingFlag,
  settingNumber,
  settingText,
  stockRowSchema,
} from '../src';

/**
 * The API contract, asserted from the client's side.
 *
 * These are the failures that do not announce themselves. A schema that no longer matches the server
 * either rejects the whole payload, which blanks a screen, or quietly turns a field into `undefined`,
 * which renders as an empty space that looks like a styling bug. Both have happened in this codebase.
 */
describe('money', () => {
  it('keeps the amount as the string the server sent', () => {
    const money = moneySchema.parse({ amount: '1899.00', currency: 'EGP' });

    // Not coerced to a number on the way in. A double round-trip is where 1899.00 starts rendering
    // as 1898.99, and the value is only ever formatted, never summed on the client.
    expect(money.amount).toBe('1899.00');
    expect(typeof money.amount).toBe('string');
  });

  it('rejects a payload missing the currency', () => {
    expect(() => moneySchema.parse({ amount: '10.00' })).toThrow();
  });
});

describe('dashboard metrics', () => {
  it('accepts an absent changePercent', () => {
    // The API returns null when the previous period was zero: growth from nothing is not a
    // percentage. Requiring a number here rejected the entire payload and blanked the dashboard for
    // any store in its first week.
    expect(() =>
      countMetricSchema.parse({ current: 1, previous: 0 }),
    ).not.toThrow();

    expect(
      metricSchema.parse({
        current: { amount: '0.00', currency: 'EGP' },
        previous: { amount: '0.00', currency: 'EGP' },
      }).changePercent,
    ).toBeUndefined();
  });

  it('accepts an explicit null changePercent', () => {
    expect(countMetricSchema.parse({ current: 1, previous: 0, changePercent: null }).changePercent)
      .toBeNull();
  });

  it('parses a whole empty-store summary', () => {
    const zero = { amount: '0.00', currency: 'EGP' };
    const summary = dashboardSummarySchema.parse({
      revenue: { current: zero, previous: zero, changePercent: 0 },
      orders: { current: 0, previous: 0, changePercent: 0 },
      averageOrderValue: { current: zero, previous: zero, changePercent: 0 },
      newCustomers: { current: 1, previous: 0 },
      awaitingPayment: 0,
      awaitingFulfilment: 0,
      pendingReviews: 0,
      outOfStockSkus: 0,
      failedPayments: 0,
    });

    expect(summary.newCustomers.current).toBe(1);
    expect(summary.newCustomers.changePercent).toBeUndefined();
  });
});

describe('admin product row', () => {
  it('carries the status the storefront card does not', () => {
    const row = adminProductRowSchema.parse({
      id: '0192a000-0000-7000-8000-00000000d001',
      sku: 'SAM-S24-256',
      slug: 'samsung-galaxy-s24-256gb',
      name: 'Samsung Galaxy S24',
      price: { amount: '32999.00', currency: 'EGP' },
      status: 'DRAFT',
      type: 'SIMPLE',
      featured: false,
      variantCount: 0,
      available: 0,
    });

    // Showing drafts alongside live products is the entire purpose of the admin list.
    expect(row.status).toBe('DRAFT');
    expect(row.available).toBe(0);
  });

  it('rejects a status the server does not define', () => {
    expect(() =>
      adminProductRowSchema.parse({
        id: '0192a000-0000-7000-8000-00000000d001',
        sku: 'X',
        slug: 'x',
        name: 'X',
        price: { amount: '1.00', currency: 'EGP' },
        status: 'PUBLISHED',
        type: 'SIMPLE',
        featured: false,
        variantCount: 0,
      }),
    ).toThrow();
  });
});

describe('admin product detail', () => {
  it('returns both languages unresolved so the editor can round-trip them', () => {
    const detail = adminProductDetailSchema.parse({
      id: '0192a000-0000-7000-8000-00000000d001',
      sku: 'SAM-S24-256',
      slug: 'samsung-galaxy-s24-256gb',
      nameAr: 'سامسونج جالاكسي S24',
      nameEn: 'Samsung Galaxy S24',
      categoryIds: [],
      basePrice: '32999.00',
      currency: 'EGP',
      status: 'ACTIVE',
      type: 'SIMPLE',
      featured: true,
      digital: false,
      requiresShipping: true,
      images: [],
      variants: [],
      attributeValueIds: [],
    });

    // A locale-resolved DTO would collapse these to one string, and saving the form would then
    // overwrite the Arabic name with the English one.
    expect(detail.nameAr).toBe('سامسونج جالاكسي S24');
    expect(detail.nameEn).toBe('Samsung Galaxy S24');

    // Prices arrive as strings from a numeric column and are coerced for the form.
    expect(detail.basePrice).toBe(32999);
  });
});

describe('stock row', () => {
  it('keeps on-hand, reserved and available distinct', () => {
    const row = stockRowSchema.parse({
      id: '0192a000-0000-7000-8000-00000000e001',
      productId: '0192a000-0000-7000-8000-00000000d001',
      productName: 'Widget',
      sku: 'W-1',
      onHand: 10,
      reserved: 4,
      available: 6,
      reorderLevel: 5,
      lowStock: false,
    });

    // available is what decides whether a customer can buy; reading onHand alone oversells.
    expect(row.available).toBe(row.onHand - row.reserved);
  });
});

describe('public settings', () => {
  const settings = publicSettingsSchema.parse({
    'store.name': { ar: 'إي-شوب', en: 'Eshop' },
    'store.currency': 'EGP',
    'store.locales': ['ar', 'en'],
    'checkout.min_order': 50,
    'checkout.cod_fee': 20,
    'checkout.guest_enabled': true,
  });

  it('passes through keys the client does not know about', () => {
    // The server decides what is public; a new setting should reach clients without a release here.
    expect(() => publicSettingsSchema.parse({ 'something.new': 1 })).not.toThrow();
  });

  it('reads a localised name for the active locale', () => {
    expect(settingText(settings, 'store.name', 'ar')).toBe('إي-شوب');
    expect(settingText(settings, 'store.name', 'en')).toBe('Eshop');
  });

  it('reads a plain string setting in either locale', () => {
    expect(settingText(settings, 'store.currency', 'ar')).toBe('EGP');
  });

  it('falls back rather than throwing on a missing key', () => {
    expect(settingText(settings, 'store.tagline', 'en', 'Shop')).toBe('Shop');
    expect(settingNumber(settings, 'checkout.max_order', 999)).toBe(999);
    expect(settingFlag(settings, 'checkout.express', false)).toBe(false);
  });

  it('reads numbers and flags with their types intact', () => {
    expect(settingNumber(settings, 'checkout.min_order', 0)).toBe(50);
    expect(settingFlag(settings, 'checkout.guest_enabled', false)).toBe(true);
  });

  it('falls back when a store has not configured a value', () => {
    // A store with no settings at all is a normal state, not an error, and the server still enforces
    // its own rules regardless of what the client managed to read.
    expect(settingNumber(undefined, 'checkout.min_order', 0)).toBe(0);
  });
});

describe('product card', () => {
  it('treats a product with no discount as not on sale', () => {
    const card = productCardSchema.parse({
      id: '0192a000-0000-7000-8000-00000000d001',
      slug: 'plain',
      sku: 'P-1',
      name: 'Plain',
      price: { amount: '100.00', currency: 'EGP' },
      discountPercent: 0,
      ratingAvg: 0,
      ratingCount: 0,
      featured: false,
      inStock: true,
    });

    expect(card.compareAtPrice).toBeFalsy();
    expect(card.discountPercent).toBe(0);
  });

  it('accepts a rating that arrives as a string from a numeric column', () => {
    // ratingAvg is numeric(2,1) in Postgres; the driver may hand it over as either.
    const card = productCardSchema.parse({
      id: '0192a000-0000-7000-8000-00000000d001',
      slug: 'plain',
      sku: 'P-1',
      name: 'Plain',
      price: { amount: '100.00', currency: 'EGP' },
      discountPercent: 0,
      ratingAvg: '4.5',
      ratingCount: 12,
      featured: false,
    });

    expect(card.ratingAvg).toBe(4.5);
  });

  it('leaves inStock undefined when availability was not requested', () => {
    // Listing endpoints omit it rather than guessing; rendering "out of stock" for a product whose
    // availability was never asked for is worse than rendering nothing.
    const card = productCardSchema.parse({
      id: '0192a000-0000-7000-8000-00000000d001',
      slug: 'plain',
      sku: 'P-1',
      name: 'Plain',
      price: { amount: '100.00', currency: 'EGP' },
      discountPercent: 0,
      ratingAvg: 0,
      ratingCount: 0,
      featured: false,
    });

    expect(card.inStock).toBeUndefined();
  });
});
