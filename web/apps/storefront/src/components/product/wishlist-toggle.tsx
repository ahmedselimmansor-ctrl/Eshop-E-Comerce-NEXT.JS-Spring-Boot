'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Heart, Loader2 } from 'lucide-react';
import type { Wishlist } from '@eshop/api-client';
import { useRouter } from '@/i18n/routing';
import { browserApi } from '@/lib/api-browser';
import { useAuthStore } from '@/stores/auth-store';
import { cn } from '@/lib/utils';

/**
 * The heart.
 *
 * Reads the whole wishlist rather than asking "is this one saved?" — the list is small, it is
 * already cached by the wishlist page, and one shared query means every heart on the site stays in
 * sync without a request each.
 *
 * A signed-out visitor is sent to login rather than shown a disabled control: the intent is clear
 * and worth preserving, and this is a good moment to ask for an account.
 */
export function WishlistToggle({
  productId,
  variantId,
  className,
}: {
  productId: string;
  variantId?: string | null;
  className?: string;
}) {
  const t = useTranslations('product');
  const router = useRouter();
  const queryClient = useQueryClient();

  const user = useAuthStore((state) => state.user);

  const { data: wishlist } = useQuery({
    queryKey: ['wishlist'],
    queryFn: () => browserApi().wishlist.get(),
    enabled: Boolean(user),
    staleTime: 30_000,
  });

  const saved = wishlist?.items.some(
    (item) => item.product.id === productId && (item.variantId ?? null) === (variantId ?? null),
  );

  const toggle = useMutation({
    mutationFn: (): Promise<Wishlist> =>
      saved
        ? browserApi().wishlist.removeProduct(productId, variantId ?? null)
        : browserApi().wishlist.add(productId, variantId ?? null),

    onSuccess: (next) => queryClient.setQueryData(['wishlist'], next),
  });

  const label = saved ? t('removeFromWishlist') : t('addToWishlist');

  return (
    <button
      type="button"
      onClick={() => {
        if (!user) {
          router.push('/auth/login');
          return;
        }
        toggle.mutate();
      }}
      disabled={toggle.isPending}
      aria-pressed={saved ?? false}
      aria-label={label}
      title={label}
      className={cn(
        'flex size-11 shrink-0 items-center justify-center rounded-[var(--radius-control)]',
        'border border-[var(--border)] transition-colors',
        'hover:border-danger-300 hover:text-danger-500 disabled:opacity-50',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]',
        saved && 'border-danger-300 text-danger-500',
        className,
      )}
    >
      {toggle.isPending ? (
        <Loader2 className="size-5 animate-spin" aria-hidden />
      ) : (
        <Heart className={cn('size-5', saved && 'fill-current')} aria-hidden />
      )}
    </button>
  );
}
