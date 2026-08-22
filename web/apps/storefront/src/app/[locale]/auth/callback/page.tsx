import { getTranslations, setRequestLocale } from 'next-intl/server';
import type { Metadata } from 'next';
import { Suspense } from 'react';
import { OAuthCallbackView } from '@/components/auth/token-forms';
import { Skeleton } from '@/components/ui/primitives';

export const metadata: Metadata = { robots: { index: false, follow: false } };

/** Where the API's Google callback redirects, carrying a single-use exchange code. */
export default async function OAuthCallbackPage({
  params,
}: {
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;
  setRequestLocale(locale);

  const t = await getTranslations('auth');
  const common = await getTranslations('common');

  return (
    <Suspense fallback={<Skeleton className="h-40 w-full" />}>
      <OAuthCallbackView
        labels={{
          loading: common('loading'),
          failed: t('verifyEmailFailed'),
          login: common('login'),
        }}
      />
    </Suspense>
  );
}
