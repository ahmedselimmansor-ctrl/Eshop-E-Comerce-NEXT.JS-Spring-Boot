import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { Suspense } from 'react';
import { PaymentResult } from '@/components/checkout/payment-result';
import { Skeleton } from '@/components/ui/primitives';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'checkout' });

  return {
    title: t('orderPlaced'),
    robots: { index: false, follow: false },
  };
}

/**
 * Where checkout lands, and where the payment provider returns the customer.
 *
 * The order already exists by the time this renders - it was committed before the provider was
 * called. What is still open is whether the payment settled, and that is genuinely uncertain here:
 * the provider's webhook and the browser redirect race each other, and the redirect usually wins.
 * So this screen polls rather than assuming.
 */
export default async function CheckoutCallbackPage({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);

  return (
    <div className="mx-auto max-w-2xl px-4 py-10">
      <Suspense fallback={<Skeleton className="h-80 w-full" />}>
        <PaymentResult locale={locale} />
      </Suspense>
    </div>
  );
}
