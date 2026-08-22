import { getTranslations, setRequestLocale } from 'next-intl/server';
import { notFound } from 'next/navigation';
import type { Metadata } from 'next';
import { ApiError } from '@eshop/api-client';
import { Link } from '@/i18n/routing';
import { serverApi } from '@/lib/api-server';
import { ProductCard } from '@/components/product/product-card';
import { FilterPanel } from '@/components/listing/filter-panel';
import { SortSelect } from '@/components/listing/sort-select';
import { Pagination } from '@/components/listing/pagination';
import { parseListingParams } from '@/lib/listing-params';

type PageProps = {
  params: Promise<{ locale: string; slug: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale, slug } = await params;

  try {
    const { category } = await (await serverApi()).catalog.category(slug);

    return {
      title: category.seo?.title ?? category.name,
      description: category.seo?.description ?? category.description ?? undefined,
      alternates: { canonical: `/${locale}/categories/${slug}` },
    };
  } catch {
    return { title: 'Category' };
  }
}

/**
 * Category listing.
 *
 * The same components as `/products`, with the category pinned. The API expands the category to
 * include its descendants, so browsing "Electronics" shows everything filed under Phones as well -
 * which is what a shopper expects and what a naive equality filter would miss.
 */
export default async function CategoryPage({ params, searchParams }: PageProps) {
  const [{ locale, slug }, query] = await Promise.all([params, searchParams]);
  setRequestLocale(locale);

  const t = await getTranslations('listing');
  const product = await getTranslations('product');
  const common = await getTranslations('common');

  const api = await serverApi();

  let detail;
  try {
    detail = await api.catalog.category(slug);
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }

  const filters = { ...parseListingParams(query), categorySlug: slug };
  const listing = await api.catalog.products(filters).catch(() => null);

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <nav aria-label="Breadcrumb" className="mb-4 flex flex-wrap items-center gap-1.5 text-xs text-[var(--muted-foreground)]">
        <Link href="/" className="hover:text-brand-700">
          {common('home')}
        </Link>
        {detail.breadcrumb.map((crumb) => (
          <span key={crumb.id} className="flex items-center gap-1.5">
            <span aria-hidden>/</span>
            <Link
              href={{ pathname: '/categories/[slug]', params: { slug: crumb.slug } }}
              className="hover:text-brand-700"
            >
              {crumb.name}
            </Link>
          </span>
        ))}
      </nav>

      <h1 className="text-2xl font-bold">{detail.category.name}</h1>
      {detail.category.description ? (
        <p className="mt-1 text-sm text-[var(--muted-foreground)]">{detail.category.description}</p>
      ) : null}

      {/* Sub-categories, as a quick way further in. */}
      {detail.children.length > 0 ? (
        <ul className="mt-4 flex flex-wrap gap-2">
          {detail.children.map((child) => (
            <li key={child.id}>
              <Link
                href={{ pathname: '/categories/[slug]', params: { slug: child.slug } }}
                className="inline-block rounded-full border border-[var(--border)] px-3 py-1.5 text-sm hover:border-brand-300 hover:text-brand-700"
              >
                {child.name}
              </Link>
            </li>
          ))}
        </ul>
      ) : null}

      {listing ? (
        <>
          <div className="mb-6 mt-6 flex flex-wrap items-center justify-between gap-3">
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
        </>
      ) : null}
    </div>
  );
}
