import { getTranslations, setRequestLocale } from 'next-intl/server';
import { notFound } from 'next/navigation';
import type { Metadata } from 'next';
import { Star } from 'lucide-react';
import { formatMoney } from '@eshop/api-client';
import { ApiError } from '@eshop/api-client';
import { Link } from '@/i18n/routing';
import { serverApi } from '@/lib/api-server';
import { Badge, Separator } from '@/components/ui/primitives';
import { ProductGallery } from '@/components/product/product-gallery';
import { ProductPurchasePanel } from '@/components/product/product-purchase-panel';
import { ProductCard } from '@/components/product/product-card';
import { truncate } from '@/lib/utils';

type PageProps = {
  params: Promise<{ locale: string; slug: string }>;
};

export async function generateMetadata({ params }: PageProps): Promise<Metadata> {
  const { locale, slug } = await params;

  try {
    const product = await (await serverApi()).catalog.product(slug);

    const title = product.seo?.title ?? product.name;
    const description =
      product.seo?.description ?? truncate(product.shortDescription ?? product.description, 160);

    return {
      title,
      description,
      alternates: {
        canonical: `/${locale}/products/${slug}`,
        languages: { ar: `/ar/منتجات/${slug}`, en: `/en/products/${slug}` },
      },
      openGraph: {
        title,
        description: description ?? undefined,
        type: 'website',
        images: product.images[0]
          ? [{ url: product.images[0].url, alt: product.images[0].alt }]
          : undefined,
      },
    };
  } catch {
    return { title: 'Product' };
  }
}

export default async function ProductPage({ params }: PageProps) {
  const { locale, slug } = await params;
  setRequestLocale(locale);

  const api = await serverApi();

  let product;
  try {
    product = await api.catalog.product(slug);
  } catch (error) {
    // A missing slug is a 404, not an error page - the difference matters for crawlers and for the
    // visitor who followed an old link.
    if (error instanceof ApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }

  const t = await getTranslations('product');
  const common = await getTranslations('common');

  const [related, reviews] = await Promise.all([
    api.catalog.related(product.id, 8).catch(() => []),
    api.catalog.reviews(product.id, 0, 5).catch(() => null),
  ]);

  return (
    <div className="mx-auto max-w-7xl px-4 py-6">
      {/* Breadcrumb - also the structured trail search engines read. */}
      <nav aria-label="Breadcrumb" className="mb-5 flex flex-wrap items-center gap-1.5 text-xs text-[var(--muted-foreground)]">
        <Link href="/" className="hover:text-brand-700">
          {common('home')}
        </Link>
        {product.breadcrumb.map((crumb) => (
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
        {product.category ? (
          <span className="flex items-center gap-1.5">
            <span aria-hidden>/</span>
            <Link
              href={{ pathname: '/categories/[slug]', params: { slug: product.category.slug } }}
              className="hover:text-brand-700"
            >
              {product.category.name}
            </Link>
          </span>
        ) : null}
      </nav>

      <div className="grid gap-8 lg:grid-cols-2">
        <ProductGallery images={product.images} productName={product.name} />

        <div>
          {product.brand ? (
            <Link
              href={{ pathname: '/brands/[slug]', params: { slug: product.brand.slug } }}
              className="text-sm text-brand-700 hover:underline"
            >
              {product.brand.name}
            </Link>
          ) : null}

          <h1 className="mt-1 text-2xl font-bold leading-tight sm:text-3xl">{product.name}</h1>

          {product.ratingCount > 0 ? (
            <a
              href="#reviews"
              className="mt-2 inline-flex items-center gap-1.5 text-sm text-[var(--muted-foreground)] hover:text-brand-700"
            >
              <span className="flex" aria-hidden>
                {Array.from({ length: 5 }, (_, index) => (
                  <Star
                    key={index}
                    className={
                      index < Math.round(product.ratingAvg)
                        ? 'size-4 fill-warning-500 text-warning-500'
                        : 'size-4 text-[var(--border)]'
                    }
                  />
                ))}
              </span>
              <span className="numeric">{product.ratingAvg.toFixed(1)}</span>
              <span>({t('reviewCount', { count: product.ratingCount })})</span>
            </a>
          ) : null}

          {product.shortDescription ? (
            <p className="mt-4 text-sm text-[var(--muted-foreground)]">{product.shortDescription}</p>
          ) : null}

          <Separator className="my-5" />

          {/*
           * Everything interactive - variant choice, quantity, add to cart - lives in this one
           * client island. The rest of the page stays a server component, so the JavaScript that
           * ships is the purchase panel and nothing else.
           */}
          <ProductPurchasePanel product={product} locale={locale} />
        </div>
      </div>

      {product.specifications.length > 0 ? (
        <section className="mt-12">
          <h2 className="mb-4 text-xl font-semibold">{t('specifications')}</h2>
          <dl className="grid gap-x-8 gap-y-2 rounded-[var(--radius-card)] border border-[var(--border)] p-5 sm:grid-cols-2">
            {product.specifications.map((spec) => (
              <div key={spec.code} className="flex justify-between gap-4 border-b border-[var(--border)] py-2 last:border-0">
                <dt className="text-sm text-[var(--muted-foreground)]">{spec.name}</dt>
                <dd className="text-sm font-medium">{spec.value}</dd>
              </div>
            ))}
          </dl>
        </section>
      ) : null}

      {product.description ? (
        <section className="mt-12">
          <h2 className="mb-4 text-xl font-semibold">{t('description')}</h2>
          {/*
           * The description is sanitised server-side before it is ever stored (see HtmlSanitizer),
           * so what reaches here is already free of scripts, styles, and event handlers. Rendering
           * it as HTML is what makes an admin-authored description look like a description rather
           * than a wall of escaped tags.
           */}
          <div
            className="prose prose-sm max-w-none [&_a]:text-brand-700 [&_a]:underline [&_img]:rounded-lg [&_ul]:list-disc [&_ul]:ps-5"
            dangerouslySetInnerHTML={{ __html: product.description }}
          />
        </section>
      ) : null}

      <section id="reviews" className="mt-12 scroll-mt-[var(--spacing-header)]">
        <h2 className="mb-4 text-xl font-semibold">{t('reviews')}</h2>

        {reviews && reviews.reviews.content.length > 0 ? (
          <div className="space-y-4">
            {reviews.reviews.content.map((review) => (
              <article
                key={review.id}
                className="rounded-[var(--radius-card)] border border-[var(--border)] p-4"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="flex" aria-label={t('stars', { count: review.rating })}>
                    {Array.from({ length: 5 }, (_, index) => (
                      <Star
                        key={index}
                        className={
                          index < review.rating
                            ? 'size-3.5 fill-warning-500 text-warning-500'
                            : 'size-3.5 text-[var(--border)]'
                        }
                        aria-hidden
                      />
                    ))}
                  </span>
                  <span className="text-sm font-medium">{review.authorName}</span>
                  {review.verifiedPurchase ? (
                    <Badge tone="success">{t('verifiedPurchase')}</Badge>
                  ) : null}
                </div>

                {review.title ? <h3 className="mt-2 font-medium">{review.title}</h3> : null}
                {review.body ? (
                  <p className="mt-1 text-sm text-[var(--muted-foreground)]">{review.body}</p>
                ) : null}

                {review.adminReply ? (
                  <div className="mt-3 rounded-md bg-[var(--muted)] p-3 text-sm">
                    <p className="font-medium text-brand-700">{common('storeName')}</p>
                    <p className="mt-1 text-[var(--muted-foreground)]">{review.adminReply}</p>
                  </div>
                ) : null}
              </article>
            ))}
          </div>
        ) : (
          <p className="text-sm text-[var(--muted-foreground)]">{t('noReviews')}</p>
        )}
      </section>

      {related.length > 0 ? (
        <section className="mt-12">
          <h2 className="mb-4 text-xl font-semibold">{t('relatedProducts')}</h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
            {related.map((item) => (
              <ProductCard
                key={item.id}
                product={item}
                locale={locale}
                labels={{
                  outOfStock: t('outOfStock'),
                  save: (percent) => t('save', { percent }),
                  reviewCount: (count) => t('reviewCount', { count }),
                }}
              />
            ))}
          </div>
        </section>
      ) : null}

      {/*
       * Product structured data.
       *
       * Feeds the rich result in search - price, availability, and rating stars beside the listing.
       * Built from the same values the page displays, so the two cannot disagree, which is what
       * Google penalises.
       */}
      <script
        type="application/ld+json"
        dangerouslySetInnerHTML={{
          __html: JSON.stringify({
            '@context': 'https://schema.org',
            '@type': 'Product',
            name: product.name,
            sku: product.sku,
            description: truncate(product.shortDescription ?? product.description, 300),
            image: product.images.map((image) => image.url),
            brand: product.brand ? { '@type': 'Brand', name: product.brand.name } : undefined,
            offers: {
              '@type': 'Offer',
              price: product.price.amount,
              priceCurrency: product.price.currency,
              availability: product.inStock
                ? 'https://schema.org/InStock'
                : 'https://schema.org/OutOfStock',
            },
            aggregateRating:
              product.ratingCount > 0
                ? {
                    '@type': 'AggregateRating',
                    ratingValue: product.ratingAvg,
                    reviewCount: product.ratingCount,
                  }
                : undefined,
          }),
        }}
      />
    </div>
  );
}
