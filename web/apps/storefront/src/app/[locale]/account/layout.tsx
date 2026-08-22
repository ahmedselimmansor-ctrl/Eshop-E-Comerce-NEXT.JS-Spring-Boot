import { getTranslations } from 'next-intl/server';
import type { ReactNode } from 'react';
import { AccountNav } from '@/components/account/account-nav';

/**
 * Shell for the signed-in account area.
 *
 * The route is already gated by the middleware, which redirects an unauthenticated visitor to
 * sign-in. That check is a routing convenience based on the presence of the refresh cookie, not an
 * authorisation decision - every request the pages below make is authorised by the backend against
 * the access token, so a forged cookie gets an empty account area rather than someone else's data.
 */
export default async function AccountLayout({ children }: { children: ReactNode }) {
  const t = await getTranslations('account');
  const common = await getTranslations('common');
  const order = await getTranslations('order');

  const items = [
    { href: '/account' as const, label: t('profile'), icon: 'user' as const },
    { href: '/account/orders' as const, label: order('myOrders'), icon: 'package' as const },
    { href: '/account/addresses' as const, label: t('addresses'), icon: 'map-pin' as const },
    { href: '/account/wishlist' as const, label: common('wishlist'), icon: 'heart' as const },
  ];

  return (
    <div className="mx-auto max-w-6xl px-4 py-8">
      <h1 className="mb-6 text-2xl font-bold">{t('title')}</h1>

      <div className="grid gap-6 lg:grid-cols-[220px_1fr] lg:items-start">
        <AccountNav items={items} />
        <div>{children}</div>
      </div>
    </div>
  );
}
