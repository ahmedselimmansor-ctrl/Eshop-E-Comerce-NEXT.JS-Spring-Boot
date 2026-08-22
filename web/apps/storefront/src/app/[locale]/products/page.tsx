import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { serverApi } from '@/lib/api-server';
import { ProductCard } from '@/components/product/product-card';
import { FilterPanel } from '@/components/listing/filter-panel';
import { SortSelect } from '@/components/listing/sort-select';
import { Pagination } from '@/components/listing/pagination';
import { parseListingParams } from '@/lib/listing-params';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'nav' });

  return {
    title: t('categories'),
    alternates: { canonical: `/${locale}/products` },
  };
}

/**
 * The product listing.
 *
 * Filters live entirely in the URL - no client state. That means a filtered view is shareable,
 * survives a reload, works with the back button, and can be rendered on the server without
 * hydration. It is also why the filter panel is a client island that only rewrites the query string
 * rather than owning the data.
 */
export default async function ProductsPage({
  params,
  searchParams,
}: {
  params: Promise<{ locale: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
}) {
  const [{ locale }, query] = await Promise.all([params, searchParams]);
  setRequestLocale(locale);

  const t = await getTranslations('listing');
  const product = await getTranslations('product');

  const filters = parseListingParams(query);
  const api = await serverApi();

  // Returns null rather than throwing: a listing that cannot load should render an empty state,
  // not an error page.
  const listing = await api.catalog.products(filters).catch(() => null);

  if (!listing) {
    return (
      <div className="mx-auto max-w-7xl px-4 py-16 text-center">
        <p className="text-lg font-medium">{t('noResults')}</p>
        <p className="mt-2 text-sm text-[var(--muted-foreground)]">{t('noResultsHint')}</p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-[var(--muted-foreground)]">
          {t('resultCount', { count: listing.page.totalElements })}
        </p>
        <SortSelect
          current={filters.sort ?? 'relevance'}
          labels={{
            sortBy: t('sortBy'),
            relevance: t('relevance'),
            priceAsc: t('priceAsc'),
            priceDesc: t('priceDesc'),
            newest: t('newest'),
            bestSelling: t('bestSelling'),
            rating: t('rating'),
          }}
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-[260px_1fr]">
        <FilterPanel
          facets={listing.facets}
          /*
           * When the search index is down the listing comes from the PostgreSQL fallback and the
           * facet counts are incomplete. Telling the panel that lets it hide the filters it cannot
           * count rather than displaying numbers that do not add up - a wrong count is worse than
           * an absent filter, because the shopper acts on it.
           */
          countsReliable={listing.searchIndexUsed}
          labels={{
            filters: t('filters'),
            price: t('price'),
            priceFrom: t('priceFrom'),
            priceTo: t('priceTo'),
            brands: t('brands'),
            categories: t('categories'),
            inStockOnly: t('inStockOnly'),
            onSaleOnly: t('onSaleOnly'),
            rating4Plus: t('rating4Plus'),
            clearFilters: t('clearFilters'),
            apply: t('filters'),
            facetsUnavailable: t('facetsUnavailable'),
          }}
        />

        <div>
          {listing.content.length === 0 ? (
            <div className="rounded-[var(--radius-card)] border border-[var(--border)] py-16 text-center">
              <p className="font-medium">{t('noResults')}</p>
              <p className="mt-1 text-sm text-[var(--muted-foreground)]">{t('noResultsHint')}</p>
            </div>
          ) : (
            <>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
                {listing.content.map((item, index) => (
                  <ProductCard
                    key={item.id}
                    product={item}
                    locale={locale}
                    priority={index < 4}
                    labels={{
                      outOfStock: product('outOfStock'),
                      save: (percent) => product('save', { percent }),
                      reviewCount: (count) => product('reviewCount', { count }),
                    }}
                  />
                ))}
              </div>

              <Pagination page={listing.page} />
            </>
          )}
        </div>
      </div>
    </div>
  );
}
