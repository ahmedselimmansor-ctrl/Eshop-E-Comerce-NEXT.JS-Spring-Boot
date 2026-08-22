'use client';

import { useQuery } from '@tanstack/react-query';
import { ShoppingBag } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Link } from '@/i18n/routing';
import { browserApi } from '@/lib/api-browser';

/**
 * Cart icon with a live item count.
 *
 * The count comes from the same TanStack Query cache the cart page uses, keyed `['cart']`. Adding
 * an item invalidates that key, so the badge updates without its own subscription or a page
 * refresh.
 */
export function CartButton() {
  const t = useTranslations('common');

  const { data } = useQuery({
    queryKey: ['cart'],
    queryFn: () => browserApi().cart.get(),
    // The header renders on every page, so this would otherwise be the most-refetched query in the
    // app. Half a minute of staleness on a badge count is imperceptible.
    staleTime: 30_000,
    // A cart that cannot be loaded should leave the badge empty, not retry in a loop behind a
    // header the visitor is trying to use.
    retry: false,
  });

  const count = data?.itemCount ?? 0;

  return (
    <Link
      href="/cart"
      className="relative inline-flex size-10 items-center justify-center rounded-[var(--radius-control)] hover:bg-[var(--muted)]"
      aria-label={count > 0 ? `${t('cart')} (${count})` : t('cart')}
    >
      <ShoppingBag className="size-5" aria-hidden />

      {count > 0 ? (
        <span
          className="numeric absolute -top-0.5 -end-0.5 flex size-5 items-center justify-center rounded-full bg-brand-600 text-[10px] font-semibold text-white"
          // The accessible name on the link already carries the count; announcing it twice is noise.
          aria-hidden
        >
          {count > 99 ? '99+' : count}
        </span>
      ) : null}
    </Link>
  );
}
