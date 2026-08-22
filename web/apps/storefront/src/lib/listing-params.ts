import type { ProductQueryParams } from '@eshop/api-client';

/**
 * Turns raw search params into a typed, bounded query.
 *
 * Shared by every listing surface - `/products`, a category, a brand, search - so the filters
 * behave identically everywhere and there is one place to change how a parameter is read.
 *
 * Every value is clamped rather than passed through: a hand-edited `?size=100000` must not become a
 * request for a hundred thousand products, and an unrecognised sort value falls back to relevance
 * instead of reaching the API and being rejected there.
 */
export function parseListingParams(
  raw: Record<string, string | string[] | undefined>,
): ProductQueryParams {
  const single = (key: string): string | undefined => {
    const value = raw[key];
    return Array.isArray(value) ? value[0] : value;
  };

  const multi = (key: string): string[] => {
    const value = raw[key];
    if (!value) return [];
    return Array.isArray(value) ? value : [value];
  };

  // Attribute facets arrive as repeated `attr_<code>` parameters - `?attr_color=red&attr_size=l`.
  const attributes: Record<string, string[]> = {};
  for (const [key, value] of Object.entries(raw)) {
    if (key.startsWith('attr_') && value) {
      attributes[key.slice(5)] = Array.isArray(value) ? value : [value];
    }
  }

  const sortValues = [
    'relevance',
    'price_asc',
    'price_desc',
    'newest',
    'best_selling',
    'rating',
  ] as const;

  const requestedSort = single('sort');
  const sort = sortValues.includes(requestedSort as never)
    ? (requestedSort as ProductQueryParams['sort'])
    : 'relevance';

  return {
    q: single('q'),
    categorySlug: single('category'),
    brandSlug: multi('brand'),
    minPrice: toNumber(single('minPrice')),
    maxPrice: toNumber(single('maxPrice')),
    minRating: toNumber(single('minRating')),
    inStock: single('inStock') === 'true' ? true : undefined,
    onSale: single('onSale') === 'true' ? true : undefined,
    sort,
    page: Math.max(0, toNumber(single('page')) ?? 0),
    size: Math.min(60, Math.max(1, toNumber(single('size')) ?? 24)),
    attributes: Object.keys(attributes).length > 0 ? attributes : undefined,
  };
}

function toNumber(value: string | undefined): number | undefined {
  if (value === undefined || value === '') return undefined;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}
