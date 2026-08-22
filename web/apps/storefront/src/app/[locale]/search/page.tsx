import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { Search as SearchIcon } from 'lucide-react';
import { Link } from '@/i18n/routing';
import { serverApi } from '@/lib/api-server';
import { ProductCard } from '@/components/product/product-card';
import { SortSelect } from '@/components/listing/sort-select';
import { Pagination } from '@/components/listing/pagination';
import { parseListingParams } from '@/lib/listing-params';

type PageProps = {
  params: Promise<{ locale: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export async function generateMetadata({ params, searchParams }: PageProps): Promise<Metadata> {
  const [{ locale }, query] = await Promise.all([params, searchParams]);
  const term = typeof query.q === 'string' ? query.q : '';
  const t = await getTranslations({ locale, namespace: 'listing' });

  return {
    title: term ? t('searchResultsFor', { query: term }) : t('filters'),
    // Search result pages are thin, near-duplicate content that dilutes a site's index. The
    // canonical pages for a crawler are the category and product pages.
    robots: { index: false, follow: true },
  };
}

export default async function SearchPage({ params, searchParams }: PageProps) {
  const [{ locale }, query] = await Promise.all([params, searchParams]);
  setRequestLocale(locale);

  const t = await getTranslations('listing');
  const product = await getTranslations('product');
  const common = await getTranslations('common');

  const term = typeof query.q === 'string' ? query.q.trim() : '';
  const api = await serverApi();

  // An empty search shows popular terms instead of an empty grid - the shopper arrived here
  // intending to look for something, and a blank page gives them nowhere to go.
  if (!term) {
    const popular = await api.search.popular(10).catch(() => []);

    return (
      <div className="mx-auto max-w-3xl px-4 py-16 text-center">
        <SearchIcon className="mx-auto size-12 text-[var(--muted-foreground)]" aria-hidden />
        <h1 className="mt-4 text-xl font-semibold">{common('search')}</h1>

        {popular.length > 0 ? (
          <ul className="mt-6 flex flex-wrap justify-center gap-2">
            {popular.map((suggestion) => (
              <li key={suggestion}>
                <Link
                  href={{ pathname: '/search', query: { q: suggestion } }}
                  className="inline-block rounded-full border border-[var(--border)] px-3 py-1.5 text-sm hover:border-brand-300 hover:text-brand-700"
                >
                  {suggestion}
                </Link>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    );
  }

  const filters = parseListingParams(query);
  const results = await api.search.query(term, filters).catch(() => null);

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <h1 className="text-xl font-semibold">{t('searchResultsFor', { query: term })}</h1>

      {!results || results.content.length === 0 ? (
        <div className="mt-8 rounded-[var(--radius-card)] border border-[var(--border)] py-16 text-center">
          <p className="font-medium">{t('noResults')}</p>
          <p className="mt-1 text-sm text-[var(--muted-foreground)]">{t('noResultsHint')}</p>
        </div>
      ) : (
        <>
          <div className="mb-6 mt-4 flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm text-[var(--muted-foreground)]">
              {t('resultCount', { count: results.page.totalElements })}
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

          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            {results.content.map((item, index) => (
              <ProductCard
                key={item.id}
                product={item}
                locale={locale}
                priority={index < 5}
                labels={{
                  outOfStock: product('outOfStock'),
                  save: (percent) => product('save', { percent }),
                  reviewCount: (count) => product('reviewCount', { count }),
                }}
              />
            ))}
          </div>

          <Pagination page={results.page} />
        </>
      )}
    </div>
  );
}
