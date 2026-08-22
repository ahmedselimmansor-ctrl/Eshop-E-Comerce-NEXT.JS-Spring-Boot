import { getTranslations, setRequestLocale } from 'next-intl/server';
import { notFound } from 'next/navigation';
import type { Metadata } from 'next';
import Image from 'next/image';
import { serverApi } from '@/lib/api-server';
import { ProductCard } from '@/components/product/product-card';
import { SortSelect } from '@/components/listing/sort-select';
import { Pagination } from '@/components/listing/pagination';
import { parseListingParams } from '@/lib/listing-params';

type PageProps = {
  params: Promise<{ locale: string; slug: string }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale, slug } = await params;

  const brand = await (await serverApi()).catalog
    .brands()
    .then((brands) => brands.find((entry) => entry.slug === slug))
    .catch(() => undefined);

  return {
    title: brand?.name ?? slug,
    description: brand?.description ?? undefined,
    alternates: { canonical: `/${locale}/brands/${slug}` },
  };
}

/**
 * Brand listing.
 *
 * Resolved from the cached brand list rather than a dedicated fetch - there are dozens of brands,
 * the list is already revalidated on a timer for the navigation, and a second round trip to read
 * one row from it would be wasteful.
 */
export default async function BrandPage({ params, searchParams }: PageProps) {
  const [{ locale, slug }, query] = await Promise.all([params, searchParams]);
  setRequestLocale(locale);

  const t = await getTranslations('listing');
  const product = await getTranslations('product');

  const api = await serverApi();
  const brands = await api.catalog.brands().catch(() => []);
  const brand = brands.find((entry) => entry.slug === slug);

  if (!brand) notFound();

  const filters = { ...parseListingParams(query), brandSlug: [slug] };
  const listing = await api.catalog.products(filters).catch(() => null);

  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <div className="flex items-center gap-4">
        {brand.logoUrl ? (
          <div className="relative size-16 shrink-0 overflow-hidden rounded-lg border border-[var(--border)] bg-white">
            <Image src={brand.logoUrl} alt="" fill sizes="64px" className="object-contain p-2" />
          </div>
        ) : null}

        <div>
          <h1 className="text-2xl font-bold">{brand.name}</h1>
          {brand.description ? (
            <p className="mt-1 text-sm text-[var(--muted-foreground)]">{brand.description}</p>
          ) : null}
        </div>
      </div>

      {listing && listing.content.length > 0 ? (
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

          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            {listing.content.map((item, index) => (
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

          <Pagination page={listing.page} />
        </>
      ) : (
        <div className="mt-8 rounded-[var(--radius-card)] border border-[var(--border)] py-16 text-center">
          <p className="font-medium">{t('noResults')}</p>
        </div>
      )}
    </div>
  );
}
