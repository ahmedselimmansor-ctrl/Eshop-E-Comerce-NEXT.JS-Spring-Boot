import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { CartView } from '@/components/cart/cart-view';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'cart' });

  return {
    title: t('title'),
    // A cart page has nothing for a search engine and everything for a session; keeping it out of
    // the index also keeps it out of any shared cache.
    robots: { index: false, follow: false },
  };
}

/**
 * The cart page.
 *
 * A thin server shell around a client component. The cart is per-visitor, changes on every
 * interaction, and must never be cached or prerendered - so there is nothing for the server to
 * render beyond the frame.
 */
export default async function CartPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('cart');
  const common = await getTranslations('common');

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="mb-6 text-2xl font-bold">{t('title')}</h1>

      <CartView
        locale={locale}
        labels={{
          empty: t('empty'),
          emptyHint: t('emptyHint'),
          continueShopping: t('continueShopping'),
          subtotal: t('subtotal'),
          discount: t('discount'),
          tax: t('tax'),
          shipping: t('shipping'),
          shippingAtCheckout: t('shippingAtCheckout'),
          total: t('total'),
          checkout: t('checkout'),
          remove: t('remove'),
          couponCode: t('couponCode'),
          couponPlaceholder: t('couponPlaceholder'),
          applyCoupon: t('applyCoupon'),
          removeCoupon: t('removeCoupon'),
          reviewIssues: t('reviewIssues'),
          fixIssues: t('fixIssues'),
          itemUnavailable: t('itemUnavailable'),
          loading: common('loading'),
          retry: common('retry'),
          somethingWentWrong: common('somethingWentWrong'),
          belowMinimum: t('belowMinimum'),
        }}
      />
    </div>
  );
}
