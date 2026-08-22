import { describe, expect, it } from 'vitest';
import {
  formatChange,
  formatDate,
  formatDateTime,
  formatMoney,
  formatMoneyCompact,
  formatNumber,
} from '../src/lib/utils';

/**
 * The console's formatting helpers.
 *
 * Every one of these is reached with a null at some point: a metric with no baseline, an order that
 * has not shipped, a stock row with no warehouse. The interesting cases are all the absent ones,
 * because that is where a helper either renders a placeholder or renders "NaN" and "Invalid Date"
 * into a page an operator is meant to act on.
 */
describe('formatMoney', () => {
  it('formats an amount in its own currency', () => {
    expect(formatMoney({ amount: '1899.00', currency: 'EGP' })).toContain('1,899');
  });

  it('renders a placeholder rather than NaN for a missing amount', () => {
    expect(formatMoney(null)).toBe('-');
    expect(formatMoney(undefined)).toBe('-');
  });

  it('formats zero as zero, not as absent', () => {
    // A refunded order really is 0.00, and showing "-" there would read as missing data.
    expect(formatMoney({ amount: '0.00', currency: 'EGP' })).toContain('0');
  });

  it('compacts a large figure for a chart axis', () => {
    const compact = formatMoneyCompact({ amount: '1250000.00', currency: 'EGP' });
    expect(compact).toMatch(/1\.2|1\.3/);
    expect(compact.length).toBeLessThan(12);
  });
});

describe('formatChange', () => {
  it('signs a positive change', () => {
    expect(formatChange(12.34)).toBe('+12.3%');
  });

  it('signs nothing for a negative change, since the minus is already there', () => {
    expect(formatChange(-8.5)).toBe('-8.5%');
  });

  it('returns null when there is no baseline to compare against', () => {
    // The API sends null when the previous period was zero. A tile showing "+Infinity%" is worse
    // than a tile showing no change indicator at all.
    expect(formatChange(null)).toBeNull();
    expect(formatChange(undefined)).toBeNull();
    expect(formatChange(Number.POSITIVE_INFINITY)).toBeNull();
    expect(formatChange(Number.NaN)).toBeNull();
  });

  it('rounds to one decimal place', () => {
    expect(formatChange(12.36)).toBe('+12.4%');
    expect(formatChange(0)).toBe('0%');
  });
});

describe('formatNumber', () => {
  it('groups thousands', () => {
    expect(formatNumber(1234567)).toBe('1,234,567');
  });

  it('distinguishes zero from absent', () => {
    expect(formatNumber(0)).toBe('0');
    expect(formatNumber(null)).toBe('-');
  });
});

describe('date formatting', () => {
  it('formats an ISO instant', () => {
    expect(formatDateTime('2026-08-22T14:30:00Z')).toContain('2026');
    expect(formatDate('2026-08-22T14:30:00Z')).toContain('2026');
  });

  it('renders a placeholder rather than "Invalid Date" for a missing timestamp', () => {
    // Orders carry several nullable timestamps: shippedAt, deliveredAt, paidAt.
    expect(formatDateTime(null)).toBe('-');
    expect(formatDate(undefined)).toBe('-');
  });
});
