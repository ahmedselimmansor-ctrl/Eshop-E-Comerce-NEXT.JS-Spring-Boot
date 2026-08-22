import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { WishlistView } from '@/components/account/wishlist-view';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'wishlist' });
  return { title: t('title'), robots: { index: false, follow: false } };
}

/**
 * The customer's saved items.
 *
 * Rendered entirely client-side rather than fetched here: the list is private, changes on every
 * heart tap across the site, and would have to be `no-store` anyway - server-rendering it would
 * cost a blocking request for markup that cannot be cached.
 */
export default async function WishlistPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('wishlist');

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold">{t('title')}</h1>
      <WishlistView locale={locale} />
    </div>
  );
}
