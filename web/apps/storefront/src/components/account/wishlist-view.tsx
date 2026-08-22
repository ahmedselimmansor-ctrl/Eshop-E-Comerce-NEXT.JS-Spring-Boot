'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Check, Copy, Heart, Link2, Loader2, ShoppingBag, Trash2 } from 'lucide-react';
import Image from 'next/image';
import { useState } from 'react';
import { ApiError, formatMoney, type Wishlist, type WishlistItem } from '@eshop/api-client';
import { getPathname, Link } from '@/i18n/routing';
import { Button } from '@/components/ui/button';
import { Badge, Card, Skeleton } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { useAuthStore } from '@/stores/auth-store';
import { cn } from '@/lib/utils';

/**
 * Saved items.
 *
 * Every mutation writes the server's response straight into the cache rather than invalidating and
 * refetching: the API returns the whole list after each change, so a round trip would buy nothing
 * but a flicker. The cart badge is invalidated separately when an item moves, because that number
 * lives in a different query.
 */
export function WishlistView({ locale }: { locale: string }) {
  const t = useTranslations('wishlist');
  const cart = useTranslations('cart');
  const product = useTranslations('product');
  const common = useTranslations('common');

  const queryClient = useQueryClient();

  const user = useAuthStore((state) => state.user);
  const initialising = useAuthStore((state) => state.initialising);

  const [copied, setCopied] = useState(false);
  const [movingId, setMovingId] = useState<string | null>(null);

  const {
    data: wishlist,
    isLoading,
    isError,
    refetch,
  } = useQuery({
    queryKey: ['wishlist'],
    queryFn: () => browserApi().wishlist.get(),
    // Guests have no wishlist to fetch - the endpoint would 401 and the retry would too.
    enabled: Boolean(user),
  });

  const write = (next: Wishlist) => queryClient.setQueryData(['wishlist'], next);

  const remove = useMutation({
    mutationFn: (itemId: string) => browserApi().wishlist.remove(itemId),
    onSuccess: write,
  });

  const clear = useMutation({
    mutationFn: () => browserApi().wishlist.clear(),
    onSuccess: write,
  });

  const share = useMutation({
    mutationFn: (isPublic: boolean) => browserApi().wishlist.setPublic(isPublic),
    onSuccess: write,
  });

  const moveToCart = useMutation({
    mutationFn: (itemId: string) => browserApi().wishlist.moveToCart(itemId, 1),
    onSuccess: (result) => {
      write(result.wishlist);
      // The cart badge in the header reads a different query key.
      queryClient.setQueryData(['cart'], result.cart);
    },
    onSettled: () => setMovingId(null),
  });

  // --- guards ---------------------------------------------------------------

  if (initialising) {
    return <Skeleton className="h-64 w-full" />;
  }

  if (!user) {
    return (
      <Card className="flex flex-col items-center gap-3 p-12 text-center">
        <Heart className="size-10 text-[var(--muted-foreground)]" aria-hidden />
        <p className="text-lg font-medium">{t('signInRequired')}</p>
        <p className="text-sm text-[var(--muted-foreground)]">{t('signInHint')}</p>
        <Button asChild className="mt-2">
          <Link href="/auth/login">{common('login')}</Link>
        </Button>
      </Card>
    );
  }

  if (isLoading) {
    return (
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-3">
        {Array.from({ length: 6 }, (_, index) => (
          <Skeleton key={index} className="h-72 w-full" />
        ))}
      </div>
    );
  }

  if (isError || !wishlist) {
    return (
      <Card className="p-8 text-center">
        <p className="font-medium">{common('somethingWentWrong')}</p>
        <Button variant="outline" className="mt-4" onClick={() => void refetch()}>
          {common('retry')}
        </Button>
      </Card>
    );
  }

  if (wishlist.items.length === 0) {
    return (
      <Card className="flex flex-col items-center gap-3 p-12 text-center">
        <Heart className="size-10 text-[var(--muted-foreground)]" aria-hidden />
        <p className="text-lg font-medium">{t('empty')}</p>
        <p className="text-sm text-[var(--muted-foreground)]">{t('emptyHint')}</p>
        <Button asChild className="mt-2">
          <Link href="/products">{cart('continueShopping')}</Link>
        </Button>
      </Card>
    );
  }

  // --- list -----------------------------------------------------------------

  const moveError = moveToCart.error instanceof ApiError ? moveToCart.error.detail : null;

  /*
   * Built from `getPathname` so the Arabic link is the Arabic URL - a shared link that reads
   * `/ar/مفضلة/…` is the one an Arabic speaker will recognise in a message.
   *
   * The origin is only knowable in the browser, and this component is prerendered, so the link
   * stays null on the server rather than guessing at a hostname.
   */
  const shareUrl =
    wishlist.shareToken && typeof window !== 'undefined'
      ? window.location.origin +
        getPathname({
          locale,
          href: { pathname: '/wishlist/[token]', params: { token: wishlist.shareToken } },
        })
      : null;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-sm text-[var(--muted-foreground)]">
          {t('savedItems', { count: wishlist.itemCount })}
        </p>

        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            loading={share.isPending}
            onClick={() => share.mutate(!wishlist.isPublic)}
          >
            <Link2 aria-hidden />
            {wishlist.isPublic ? t('stopSharing') : t('share')}
          </Button>

          <Button
            variant="ghost"
            size="sm"
            loading={clear.isPending}
            onClick={() => {
              if (window.confirm(t('clearConfirm'))) clear.mutate();
            }}
          >
            <Trash2 aria-hidden />
            {t('clearAll')}
          </Button>
        </div>
      </div>

      {/* The share link, only once there is one to show. */}
      {wishlist.isPublic && shareUrl ? (
        <Card className="flex flex-wrap items-center justify-between gap-3 p-4">
          <div className="min-w-0">
            <p className="text-sm font-medium">{t('shareOn')}</p>
            <p className="truncate text-xs text-[var(--muted-foreground)]">{shareUrl}</p>
          </div>

          <Button
            variant="outline"
            size="sm"
            onClick={async () => {
              await navigator.clipboard.writeText(shareUrl);
              setCopied(true);
              setTimeout(() => setCopied(false), 2000);
            }}
          >
            {copied ? <Check aria-hidden /> : <Copy aria-hidden />}
            {copied ? t('linkCopied') : t('copyLink')}
          </Button>
        </Card>
      ) : null}

      {moveError ? (
        <p role="alert" className="text-sm text-danger-500">
          {moveError}
        </p>
      ) : null}

      <ul className="grid grid-cols-2 gap-4 lg:grid-cols-3">
        {wishlist.items.map((item) => (
          <li key={item.id}>
            <SavedCard
              item={item}
              locale={locale}
              moving={movingId === item.id && moveToCart.isPending}
              removing={remove.isPending && remove.variables === item.id}
              labels={{
                remove: t('remove'),
                moveToCart: t('moveToCart'),
                soldOut: t('soldOut'),
                outOfStock: product('outOfStock'),
                save: (percent: number) => product('save', { percent }),
              }}
              onRemove={() => remove.mutate(item.id)}
              onMove={() => {
                setMovingId(item.id);
                moveToCart.mutate(item.id);
              }}
            />
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * One saved product.
 *
 * Close to `ProductCard` but not the same component: this one is an interactive card with its own
 * controls, and the whole tile cannot be a link when it contains two buttons.
 */
function SavedCard({
  item,
  locale,
  moving,
  removing,
  labels,
  onRemove,
  onMove,
}: {
  item: WishlistItem;
  locale: string;
  moving: boolean;
  removing: boolean;
  labels: {
    remove: string;
    moveToCart: string;
    soldOut: string;
    outOfStock: string;
    save: (percent: number) => string;
  };
  onRemove: () => void;
  onMove: () => void;
}) {
  const { product } = item;

  return (
    <Card className="flex h-full flex-col overflow-hidden">
      <div className="relative aspect-product bg-[var(--muted)]">
        <Link
          href={{ pathname: '/products/[slug]', params: { slug: product.slug } }}
          className="absolute inset-0"
        >
          {product.imageUrl ? (
            <Image
              src={product.imageUrl}
              alt={product.name}
              fill
              sizes="(max-width: 640px) 50vw, 33vw"
              className={cn('object-cover', !item.inStock && 'opacity-60')}
              placeholder={product.imageBlurhash ? 'blur' : 'empty'}
              blurDataURL={product.imageBlurhash ?? undefined}
            />
          ) : null}
        </Link>

        {product.discountPercent > 0 && item.inStock ? (
          <Badge tone="sale" className="absolute top-2 start-2">
            {labels.save(product.discountPercent)}
          </Badge>
        ) : null}

        {/* Removal sits over the image, top-end, mirroring where the heart was when it was saved. */}
        <button
          type="button"
          onClick={onRemove}
          disabled={removing}
          aria-label={labels.remove}
          title={labels.remove}
          className={cn(
            'absolute top-2 end-2 flex size-8 items-center justify-center rounded-full',
            'bg-[var(--card)]/90 text-[var(--muted-foreground)] shadow-sm backdrop-blur',
            'transition-colors hover:text-danger-500 disabled:opacity-50',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]',
          )}
        >
          {removing ? (
            <Loader2 className="size-4 animate-spin" aria-hidden />
          ) : (
            <Trash2 className="size-4" aria-hidden />
          )}
        </button>

        {!item.inStock ? (
          <div className="absolute inset-x-0 bottom-0 bg-black/50 p-1.5 text-center">
            <span className="text-xs font-medium text-white">{labels.outOfStock}</span>
          </div>
        ) : null}
      </div>

      <div className="flex flex-1 flex-col gap-1.5 p-3">
        {product.brandName ? (
          <span className="text-xs text-[var(--muted-foreground)]">{product.brandName}</span>
        ) : null}

        <Link
          href={{ pathname: '/products/[slug]', params: { slug: product.slug } }}
          className="line-clamp-2 text-sm font-medium leading-snug hover:text-brand-700"
        >
          {product.name}
        </Link>

        {item.variantLabel ? (
          <span className="text-xs text-[var(--muted-foreground)]">{item.variantLabel}</span>
        ) : null}

        <div className="mt-auto flex flex-wrap items-baseline gap-2 pt-1">
          <span className="numeric text-base font-semibold">
            {formatMoney(product.price, locale)}
          </span>
          {product.compareAtPrice ? (
            <span className="numeric text-xs text-[var(--muted-foreground)] line-through">
              {formatMoney(product.compareAtPrice, locale)}
            </span>
          ) : null}
        </div>

        {/* Sold out keeps the item - losing a saved product because it is temporarily unavailable
            would be exactly the wrong behaviour for a wishlist. */}
        {item.inStock ? (
          <Button size="sm" fullWidth loading={moving} onClick={onMove} className="mt-2">
            <ShoppingBag aria-hidden />
            {labels.moveToCart}
          </Button>
        ) : (
          <p className="mt-2 text-xs text-[var(--muted-foreground)]">{labels.soldOut}</p>
        )}
      </div>
    </Card>
  );
}
