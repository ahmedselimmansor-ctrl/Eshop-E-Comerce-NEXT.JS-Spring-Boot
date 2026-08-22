import { getTranslations, setRequestLocale } from 'next-intl/server';
import { Suspense } from 'react';
import Image from 'next/image';
import { ArrowLeft } from 'lucide-react';
import type { ProductCard as ProductCardData } from '@eshop/api-client';
import { Link } from '@/i18n/routing';
import { serverApi } from '@/lib/api-server';
import { Button } from '@/components/ui/button';
import { ProductCard, ProductCardSkeleton } from '@/components/product/product-card';

/**
 * The home page.
 *
 * Each shelf is wrapped in its own `<Suspense>` boundary so they stream independently: the hero and
 * the header paint immediately, and a slow "best sellers" query cannot hold up the rest of the
 * page. That is also why the shelves are separate async components rather than one `Promise.all` —
 * the whole point is that they do not wait for each other.
 */
export default async function HomePage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('home');

  return (
    <>
      <Hero locale={locale} />

      <div className="mx-auto max-w-7xl space-y-12 px-4 py-12">
        <Suspense fallback={<ShelfSkeleton title={t('featured')} />}>
          <FeaturedShelf locale={locale} title={t('featured')} />
        </Suspense>

        <Suspense fallback={<CategoryStripSkeleton title={t('shopByCategory')} />}>
          <CategoryStrip locale={locale} title={t('shopByCategory')} />
        </Suspense>

        <Suspense fallback={<ShelfSkeleton title={t('newArrivals')} />}>
          <NewArrivalsShelf locale={locale} title={t('newArrivals')} />
        </Suspense>

        <Suspense fallback={<ShelfSkeleton title={t('bestSellers')} />}>
          <BestSellersShelf locale={locale} title={t('bestSellers')} />
        </Suspense>
      </div>
    </>
  );
}

async function Hero({ locale }: { locale: string }) {
  const t = await getTranslations('home');

  return (
    <section className="bg-gradient-to-b from-brand-50 to-[var(--background)]">
      <div className="mx-auto flex max-w-7xl flex-col items-start gap-4 px-4 py-14 sm:py-20">
        <h1 className="max-w-2xl text-3xl font-bold leading-tight sm:text-5xl">{t('heroTitle')}</h1>
        <p className="max-w-xl text-base text-[var(--muted-foreground)] sm:text-lg">
          {t('heroSubtitle')}
        </p>

        <Button asChild size="lg" className="mt-2">
          <Link href="/products">
            {t('shopNow')}
            {/* rtl-flip turns the arrow around in Arabic, so "forward" points the right way. */}
            <ArrowLeft className="rtl-flip" aria-hidden />
          </Link>
        </Button>
      </div>
    </section>
  );
}

async function FeaturedShelf({ locale, title }: { locale: string; title: string }) {
  const products = await (await serverApi()).catalog.featured(8).catch(() => []);
  return <Shelf title={title} products={products} locale={locale} priorityImages />;
}

async function NewArrivalsShelf({ locale, title }: { locale: string; title: string }) {
  const products = await (await serverApi()).catalog.newArrivals(8).catch(() => []);
  return <Shelf title={title} products={products} locale={locale} />;
}

async function BestSellersShelf({ locale, title }: { locale: string; title: string }) {
  const products = await (await serverApi()).catalog.bestSellers(8).catch(() => []);
  return <Shelf title={title} products={products} locale={locale} />;
}

async function Shelf({
  title,
  products,
  locale,
  priorityImages = false,
}: {
  title: string;
  products: ProductCardData[];
  locale: string;
  priorityImages?: boolean;
}) {
  // A shelf whose query failed renders nothing rather than an empty box with a heading — a heading
  // over emptiness reads as "we have none of these", which is not what happened.
  if (products.length === 0) return null;

  const common = await getTranslations('common');
  const product = await getTranslations('product');

  return (
    <section>
      <div className="mb-4 flex items-baseline justify-between gap-4">
        <h2 className="text-xl font-semibold sm:text-2xl">{title}</h2>
        <Link href="/products" className="text-sm text-brand-700 hover:underline">
          {common('viewAll')}
        </Link>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        {products.map((item, index) => (
          <ProductCard
            key={item.id}
            product={item}
            locale={locale}
            // Only the first row of the first shelf — that is the LCP candidate. Marking every
            // image priority tells the browser nothing.
            priority={priorityImages && index < 4}
            labels={{
              outOfStock: product('outOfStock'),
              save: (percent) => product('save', { percent }),
              reviewCount: (count) => product('reviewCount', { count }),
            }}
          />
        ))}
      </div>
    </section>
  );
}

async function CategoryStrip({ locale, title }: { locale: string; title: string }) {
  const categories = await (await serverApi()).catalog.categoryTree().catch(() => []);
  if (categories.length === 0) return null;

  return (
    <section>
      <h2 className="mb-4 text-xl font-semibold sm:text-2xl">{title}</h2>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
        {categories.slice(0, 12).map((category) => (
          <Link
            key={category.id}
            href={{ pathname: '/categories/[slug]', params: { slug: category.slug } }}
            className="group flex flex-col items-center gap-2 rounded-[var(--radius-card)] border border-[var(--border)] bg-[var(--card)] p-4 text-center transition-shadow hover:shadow-[var(--shadow-card-hover)]"
          >
            <div className="relative size-16 overflow-hidden rounded-full bg-[var(--muted)]">
              {category.imageUrl ? (
                <Image
                  src={category.imageUrl}
                  alt=""
                  fill
                  sizes="64px"
                  className="object-cover transition-transform group-hover:scale-110"
                />
              ) : null}
            </div>
            <span className="line-clamp-2 text-sm font-medium">{category.name}</span>
          </Link>
        ))}
      </div>
    </section>
  );
}

function ShelfSkeleton({ title }: { title: string }) {
  return (
    <section>
      <h2 className="mb-4 text-xl font-semibold sm:text-2xl">{title}</h2>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        {Array.from({ length: 4 }, (_, index) => (
          <ProductCardSkeleton key={index} />
        ))}
      </div>
    </section>
  );
}

function CategoryStripSkeleton({ title }: { title: string }) {
  return (
    <section>
      <h2 className="mb-4 text-xl font-semibold sm:text-2xl">{title}</h2>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
        {Array.from({ length: 6 }, (_, index) => (
          <div key={index} className="skeleton h-32 rounded-[var(--radius-card)]" />
        ))}
      </div>
    </section>
  );
}
