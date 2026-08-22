import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { RegisterForm } from '@/components/auth/register-form';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'auth' });

  return { title: t('registerTitle'), robots: { index: false, follow: false } };
}

export default async function RegisterPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('auth');
  const common = await getTranslations('common');
  const errors = await getTranslations('errors');

  return (
    <RegisterForm
      labels={{
        title: t('registerTitle'),
        subtitle: t('registerSubtitle'),
        firstName: t('firstName'),
        lastName: t('lastName'),
        email: t('email'),
        phone: t('phone'),
        password: t('password'),
        marketingOptIn: t('marketingOptIn'),
        submit: common('register'),
        haveAccount: t('haveAccount'),
        login: common('login'),
        continueWithGoogle: t('continueWithGoogle'),
        orContinueWith: t('orContinueWith'),
        optional: common('optional'),
        emailTaken: errors('EMAIL_ALREADY_REGISTERED'),
        rateLimited: errors('RATE_LIMITED'),
        somethingWentWrong: common('somethingWentWrong'),
      }}
    />
  );
}
