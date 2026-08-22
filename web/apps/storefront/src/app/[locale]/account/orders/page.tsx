import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { OrdersView } from '@/components/account/orders-view';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'order' });
  return { title: t('myOrders'), robots: { index: false, follow: false } };
}

export default async function OrdersPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('order');

  return (
    <div className="space-y-4">
      <h1 className="text-xl font-bold">{t('myOrders')}</h1>
      <OrdersView locale={locale} />
    </div>
  );
}
