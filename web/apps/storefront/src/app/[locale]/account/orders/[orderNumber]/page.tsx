import { setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { OrderDetailView } from '@/components/account/order-detail-view';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string; orderNumber: string }>;
}): Promise<Metadata> {
  const { orderNumber } = await params;
  return { title: orderNumber, robots: { index: false, follow: false } };
}

export default async function OrderDetailPage({
  params,
}: {
  params: Promise<{ locale: string; orderNumber: string }>;
}) {
  const { locale, orderNumber } = await params;
  setRequestLocale(locale);

  return <OrderDetailView orderNumber={orderNumber} locale={locale} />;
}
