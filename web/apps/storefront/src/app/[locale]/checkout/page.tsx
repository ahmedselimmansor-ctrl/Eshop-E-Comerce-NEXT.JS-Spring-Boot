import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { CheckoutFlow } from '@/components/checkout/checkout-flow';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'checkout' });

  return {
    title: t('title'),
    robots: { index: false, follow: false },
  };
}

/**
 * Checkout.
 *
 * A thin server shell. The flow itself is a client component that reads its own messages - it needs
 * parameterised strings ("2–3 business days"), and a server component cannot pass a translation
 * function across the boundary.
 */
export default async function CheckoutPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('checkout');

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="mb-6 text-2xl font-bold">{t('title')}</h1>
      <CheckoutFlow locale={locale} />
    </div>
  );
}
