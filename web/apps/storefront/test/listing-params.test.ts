import { describe, expect, it } from 'vitest';
import { parseListingParams } from '../src/lib/listing-params';

/**
 * Listing parameters, parsed from a URL a stranger controls.
 *
 * Every field here is clamped rather than passed through, and the reason is not tidiness: a
 * hand-edited `?size=100000` reaching the API is a request for a hundred thousand rows, and an
 * unrecognised sort value would be rejected by the server as a 400 that reads to the customer as a
 * broken page. These tests exist to keep both of those impossible.
 */
describe('parseListingParams', () => {
  it('applies sensible defaults for an empty query', () => {
    const params = parseListingParams({});

    expect(params.page).toBe(0);
    expect(params.size).toBe(24);
    expect(params.sort).toBe('relevance');
    expect(params.q).toBeUndefined();
  });

  it('clamps an oversized page size', () => {
    expect(parseListingParams({ size: '100000' }).size).toBe(60);
    expect(parseListingParams({ size: '0' }).size).toBe(1);
    expect(parseListingParams({ size: '-5' }).size).toBe(1);
  });

  it('clamps a negative page to the first one', () => {
    expect(parseListingParams({ page: '-3' }).page).toBe(0);
  });

  it('falls back to relevance for a sort the API does not accept', () => {
    expect(parseListingParams({ sort: 'price_asc' }).sort).toBe('price_asc');
    expect(parseListingParams({ sort: 'cheapest' }).sort).toBe('relevance');
    expect(parseListingParams({ sort: '' }).sort).toBe('relevance');
  });

  it('ignores non-numeric numbers rather than sending NaN', () => {
    const params = parseListingParams({ minPrice: 'abc', maxPrice: '', page: 'x' });

    expect(params.minPrice).toBeUndefined();
    expect(params.maxPrice).toBeUndefined();
    expect(params.page).toBe(0);
  });

  it('accepts repeated brand parameters as a list', () => {
    expect(parseListingParams({ brand: ['samsung', 'apple'] }).brandSlug).toEqual([
      'samsung',
      'apple',
    ]);
    expect(parseListingParams({ brand: 'samsung' }).brandSlug).toEqual(['samsung']);
    expect(parseListingParams({}).brandSlug).toEqual([]);
  });

  it('collects attribute facets from attr_ prefixed parameters', () => {
    const params = parseListingParams({ attr_color: ['red', 'blue'], attr_size: 'l', q: 'phone' });

    expect(params.attributes).toEqual({ color: ['red', 'blue'], size: ['l'] });
    expect(params.q).toBe('phone');
  });

  it('leaves attributes undefined when none were given, rather than sending an empty object', () => {
    expect(parseListingParams({ q: 'phone' }).attributes).toBeUndefined();
  });

  it('treats the boolean filters as opt-in only', () => {
    // `?inStock=false` must not become `inStock: false`, which would filter to out-of-stock
    // products. Absent and false mean the same thing here: do not filter.
    expect(parseListingParams({ inStock: 'true' }).inStock).toBe(true);
    expect(parseListingParams({ inStock: 'false' }).inStock).toBeUndefined();
    expect(parseListingParams({ onSale: 'true' }).onSale).toBe(true);
    expect(parseListingParams({ onSale: 'yes' }).onSale).toBeUndefined();
  });

  it('takes the first value when a single-valued parameter is repeated', () => {
    expect(parseListingParams({ q: ['phone', 'laptop'] }).q).toBe('phone');
  });
});
