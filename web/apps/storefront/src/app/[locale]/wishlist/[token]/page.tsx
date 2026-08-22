import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { Heart } from 'lucide-react';
import { ProductCard } from '@/components/product/product-card';
import { Card } from '@/components/ui/primitives';
import { serverApi } from '@/lib/api-server';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'wishlist' });

  return {
    title: t('sharedTitle'),
    // Not indexed. The token is a capability, and a shared list in search results would defeat the
    // point of it being unguessable.
    robots: { index: false, follow: false },
  };
}

/**
 * Someone else's shared wishlist.
 *
 * Server-rendered, unlike the owner's own list: the viewer is usually signed out, the token is the
 * whole authorisation, and the page is a read-only grid with nothing interactive on it.
 */
export default async function SharedWishlistPage({
  params,
}: {
  params: Promise<{ locale: string; token: string }>;
}) {
  const { locale, token } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('wishlist');
  const product = await getTranslations('product');

  const api = await serverApi();
  // A revoked or mistyped token is indistinguishable from a list that never existed, which is
  // exactly what it should look like from outside.
  const wishlist = await api.wishlist.shared(token, { cache: 'no-store' }).catch(() => null);

  if (!wishlist) {
    notFound();
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <div className="mb-6 flex items-center gap-3">
        <Heart className="size-6 text-brand-600" aria-hidden />
        <div>
          <h1 className="text-xl font-bold">{t('sharedTitle')}</h1>
          <p className="text-sm text-[var(--muted-foreground)]">{t('sharedHint')}</p>
        </div>
      </div>

      {wishlist.items.length === 0 ? (
        <Card className="p-12 text-center">
          <p className="text-lg font-medium">{t('empty')}</p>
        </Card>
      ) : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {wishlist.items.map((item, index) => (
            <ProductCard
              key={item.id}
              product={item.product}
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
      )}
    </div>
  );
}
