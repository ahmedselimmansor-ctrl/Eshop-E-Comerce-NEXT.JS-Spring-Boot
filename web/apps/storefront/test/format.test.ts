import { describe, expect, it } from 'vitest';
import { formatMoney } from '@eshop/api-client';

/**
 * Money as a customer sees it, in both languages.
 *
 * Arabic deliberately uses Western digits. EGP prices are overwhelmingly written that way in
 * Egyptian commerce, and a page that mixes Eastern Arabic numerals in the totals with Western ones
 * in the product cards reads as a bug rather than as localisation.
 */
describe('formatMoney', () => {
  it('formats in English', () => {
    const formatted = formatMoney({ amount: '1899.00', currency: 'EGP' }, 'en');

    expect(formatted).toContain('1,899');
    expect(formatted).toMatch(/EGP|E£/);
  });

  it('uses Western digits in Arabic', () => {
    const formatted = formatMoney({ amount: '1899.00', currency: 'EGP' }, 'ar');

    expect(formatted).toMatch(/1[,٬]?899/);
    // No Eastern Arabic numerals anywhere in the output.
    expect(formatted).not.toMatch(/[٠-٩]/);
  });

  it('always shows two decimal places', () => {
    expect(formatMoney({ amount: '10', currency: 'EGP' }, 'en')).toContain('10.00');
    expect(formatMoney({ amount: '10.5', currency: 'EGP' }, 'en')).toContain('10.50');
  });

  it('returns an empty string for a missing amount rather than "NaN"', () => {
    expect(formatMoney(null, 'en')).toBe('');
    expect(formatMoney(undefined, 'ar')).toBe('');
  });

  it('formats zero rather than treating it as absent', () => {
    expect(formatMoney({ amount: '0.00', currency: 'EGP' }, 'en')).toContain('0.00');
  });

  it('honours a currency other than the store default', () => {
    expect(formatMoney({ amount: '25.00', currency: 'USD' }, 'en')).toMatch(/\$|USD/);
  });
});
