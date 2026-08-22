import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { ProfileForm } from '@/components/account/profile-form';

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'account' });
  return { title: t('profile'), robots: { index: false, follow: false } };
}

export default async function AccountPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('account');
  const auth = await getTranslations('auth');
  const common = await getTranslations('common');

  return (
    <ProfileForm
      labels={{
        profile: t('profile'),
        firstName: auth('firstName'),
        lastName: auth('lastName'),
        email: auth('email'),
        phone: auth('phone'),
        language: common('language'),
        arabic: common('arabic'),
        english: common('english'),
        marketingOptIn: auth('marketingOptIn'),
        save: common('save'),
        saved: t('profileUpdated'),
        security: t('security'),
        changePassword: t('changePassword'),
        currentPassword: auth('currentPassword'),
        newPassword: auth('newPassword'),
        confirmPassword: auth('confirmPassword'),
        passwordChanged: t('passwordChanged'),
        mismatch: 'كلمتا المرور غير متطابقتين',
        optional: common('optional'),
        somethingWentWrong: common('somethingWentWrong'),
      }}
    />
  );
}
