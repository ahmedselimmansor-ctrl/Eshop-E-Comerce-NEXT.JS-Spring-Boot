import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { Suspense } from 'react';
import { ForgotPasswordForm } from '@/components/auth/token-forms';
import { Skeleton } from '@/components/ui/primitives';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'auth' });
  return { title: t('forgotTitle'), robots: { index: false, follow: false } };
}

export default async function ForgotPasswordPage({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('auth');
  const common = await getTranslations('common');

  return (
    <Suspense fallback={<Skeleton className="h-72 w-full" />}>
      <ForgotPasswordForm
        labels={{
          title: t('forgotTitle'),
          subtitle: t('forgotSubtitle'),
          email: t('email'),
          submit: common('confirm'),
          sent: t('forgotSent'),
          backToLogin: common('login'),
          somethingWentWrong: common('somethingWentWrong'),
        }}
      />
    </Suspense>
  );
}
