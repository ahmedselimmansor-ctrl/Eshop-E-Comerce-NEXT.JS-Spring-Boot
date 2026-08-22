import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { Suspense } from 'react';
import { TrackOrderForm } from '@/components/account/track-order-form';
import { Skeleton } from '@/components/ui/primitives';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'order' });
  return { title: t('trackTitle'), robots: { index: false, follow: true } };
}

/** Guest order tracking — no account needed, but both the order number and the email are required. */
export default async function TrackOrderPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('order');
  const auth = await getTranslations('auth');
  const common = await getTranslations('common');

  return (
    <div className="mx-auto max-w-lg px-4 py-10">
      <Suspense fallback={<Skeleton className="h-72 w-full" />}>
        <TrackOrderForm
          locale={locale}
          labels={{
            title: t('trackTitle'),
            hint: t('trackHint'),
            orderNumber: t('orderDate'),
            email: auth('email'),
            submit: t('trackButton'),
            notFound: common('notFound'),
            somethingWentWrong: common('somethingWentWrong'),
            status: t('timeline'),
          }}
        />
      </Suspense>
    </div>
  );
}
