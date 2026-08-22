import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { Suspense } from 'react';
import { LoginForm } from '@/components/auth/login-form';
import { Skeleton } from '@/components/ui/primitives';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'auth' });

  return {
    title: t('loginTitle'),
    robots: { index: false, follow: false },
  };
}

export default async function LoginPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('auth');
  const common = await getTranslations('common');
  const errors = await getTranslations('errors');

  return (
    <Suspense fallback={<Skeleton className="h-96 w-full" />}>
      <LoginForm
        labels={{
          title: t('loginTitle'),
          subtitle: t('loginSubtitle'),
          emailOrPhone: t('emailOrPhone'),
          password: t('password'),
          rememberMe: t('rememberMe'),
          forgotPassword: t('forgotPassword'),
          submit: common('login'),
          noAccount: t('noAccount'),
          register: common('register'),
          continueWithGoogle: t('continueWithGoogle'),
          orContinueWith: t('orContinueWith'),
          invalidCredentials: errors('INVALID_CREDENTIALS'),
          accountLocked: errors('ACCOUNT_LOCKED'),
          rateLimited: errors('RATE_LIMITED'),
          somethingWentWrong: common('somethingWentWrong'),
        }}
      />
    </Suspense>
  );
}
