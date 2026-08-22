import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { Suspense } from 'react';
import { VerifyEmailView } from '@/components/auth/token-forms';
import { Skeleton } from '@/components/ui/primitives';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'auth' });
  return { title: t('verifyEmailTitle'), robots: { index: false, follow: false } };
}

export default async function VerifyEmailPage({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('auth');
  const common = await getTranslations('common');

  return (
    <Suspense fallback={<Skeleton className="h-56 w-full" />}>
      <VerifyEmailView
        labels={{
          title: t('verifyEmailTitle'),
          success: t('verifyEmailSuccess'),
          failed: t('verifyEmailFailed'),
          continueShopping: common('backToHome'),
          loading: common('loading'),
        }}
      />
    </Suspense>
  );
}
