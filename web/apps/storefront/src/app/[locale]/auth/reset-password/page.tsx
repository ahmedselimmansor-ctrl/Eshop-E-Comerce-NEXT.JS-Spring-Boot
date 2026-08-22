import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { Suspense } from 'react';
import { ResetPasswordForm } from '@/components/auth/token-forms';
import { Skeleton } from '@/components/ui/primitives';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'auth' });
  return { title: t('resetTitle'), robots: { index: false, follow: false } };
}

export default async function ResetPasswordPage({
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
      <ResetPasswordForm
        labels={{
          title: t('resetTitle'),
          newPassword: t('newPassword'),
          confirmPassword: t('confirmPassword'),
          submit: common('save'),
          success: t('resetSuccess'),
          login: common('login'),
          mismatch: 'كلمتا المرور غير متطابقتين',
          invalidLink: t('verifyEmailFailed'),
          somethingWentWrong: common('somethingWentWrong'),
        }}
      />
    </Suspense>
  );
}
