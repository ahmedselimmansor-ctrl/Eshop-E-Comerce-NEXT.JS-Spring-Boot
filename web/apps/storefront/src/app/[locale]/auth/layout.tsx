import type { ReactNode } from 'react';
import { getTranslations } from 'next-intl/server';
import { Link } from '@/i18n/routing';

/**
 * Shell for every authentication screen.
 *
 * Narrow and centred, with the store name as the only navigation. Sign-in is the one place where
 * the usual header - search, categories, cart - is a distraction from the single thing the visitor
 * came to do.
 */
export default async function AuthLayout({ children }: { children: ReactNode }) {
  const t = await getTranslations('common');

  return (
    <div className="mx-auto flex min-h-[70vh] w-full max-w-md flex-col justify-center px-4 py-10">
      <div className="mb-6 text-center">
        <Link href="/" className="text-2xl font-bold text-brand-700">
          {t('storeName')}
        </Link>
      </div>

      {children}
    </div>
  );
}
