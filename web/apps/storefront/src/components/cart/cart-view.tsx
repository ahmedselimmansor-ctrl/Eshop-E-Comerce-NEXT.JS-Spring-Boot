'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Minus, Plus, ShoppingBag, Trash2 } from 'lucide-react';
import Image from 'next/image';
import { useState } from 'react';
import { ApiError, formatMoney, type Cart } from '@eshop/api-client';
import { Link } from '@/i18n/routing';
import { Button } from '@/components/ui/button';
import { Card, Input, Separator, Skeleton } from '@/components/ui/primitives';
import { settingNumber } from '@eshop/api-client';
import { browserApi } from '@/lib/api-browser';

interface Labels {
  empty: string;
  emptyHint: string;
  continueShopping: string;
  subtotal: string;
  discount: string;
  tax: string;
  shipping: string;
  shippingAtCheckout: string;
  total: string;
  checkout: string;
  remove: string;
  couponCode: string;
  couponPlaceholder: string;
  applyCoupon: string;
  removeCoupon: string;
  reviewIssues: string;
  fixIssues: string;
  itemUnavailable: string;
  loading: string;
  retry: string;
  somethingWentWrong: string;
  belowMinimum: string;
}

export function CartView({ locale, labels }: { locale: string; labels: Labels }) {
  const queryClient = useQueryClient();
  const [couponInput, setCouponInput] = useState('');

  const { data: cart, isLoading, isError, refetch } = useQuery({
    queryKey: ['cart'],
    queryFn: () => browserApi().cart.get(),
  });

  /**
   * The store's own rules, so the cart can enforce the minimum here rather than letting checkout
   * reject it after the customer has filled in an address.
   *
   * Failure is not surfaced: if this request does not land, the minimum falls back to zero and the
   * server still refuses a cart that is too small. A settings outage should not stop people buying.
   */
  const { data: settings } = useQuery({
    queryKey: ['public-settings'],
    queryFn: () => browserApi().settings.public(),
    staleTime: 5 * 60_000,
    retry: 1,
  });

  const minimumOrder = settingNumber(settings, 'checkout.min_order', 0);
  const subtotal = Number(cart?.totals.subtotal.amount ?? 0);
  const belowMinimum = minimumOrder > 0 && subtotal < minimumOrder;

  /**
   * Every mutation writes the returned cart straight into the cache.
   *
   * The API returns the whole recalculated cart, so there is no reason to invalidate and refetch -
   * that would show stale totals for a round trip and make the line items flicker.
   */
  function seedCart(next: Cart) {
    queryClient.setQueryData(['cart'], next);
  }

  const updateQuantity = useMutation({
    mutationFn: ({ itemId, quantity }: { itemId: string; quantity: number }) =>
      browserApi().cart.updateItem(itemId, quantity),
    onSuccess: seedCart,
  });

  const removeItem = useMutation({
    mutationFn: (itemId: string) => browserApi().cart.removeItem(itemId),
    onSuccess: seedCart,
  });

  const applyCoupon = useMutation({
    mutationFn: (code: string) => browserApi().cart.applyCoupon(code),
    onSuccess: (next) => {
      seedCart(next);
      setCouponInput('');
    },
  });

  const removeCoupon = useMutation({
    mutationFn: () => browserApi().cart.removeCoupon(),
    onSuccess: seedCart,
  });

  const reconcile = useMutation({
    mutationFn: () => browserApi().cart.reconcile(),
    onSuccess: seedCart,
  });

  if (isLoading) {
    return (
      <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
        <div className="space-y-3">
          {Array.from({ length: 3 }, (_, index) => (
            <Skeleton key={index} className="h-28 w-full" />
          ))}
        </div>
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (isError) {
    return (
      <Card className="p-8 text-center">
        <p className="font-medium">{labels.somethingWentWrong}</p>
        <Button variant="outline" className="mt-4" onClick={() => void refetch()}>
          {labels.retry}
        </Button>
      </Card>
    );
  }

  if (!cart || cart.items.length === 0) {
    return (
      <Card className="flex flex-col items-center gap-3 p-12 text-center">
        <ShoppingBag className="size-12 text-[var(--muted-foreground)]" aria-hidden />
        <p className="text-lg font-medium">{labels.empty}</p>
        <p className="text-sm text-[var(--muted-foreground)]">{labels.emptyHint}</p>
        <Button asChild className="mt-2">
          <Link href="/products">{labels.continueShopping}</Link>
        </Button>
      </Card>
    );
  }

  const couponError = applyCoupon.error instanceof ApiError ? applyCoupon.error.detail : null;
  const hasIssues = cart.items.some((item) => !item.inStock || item.priceChanged);

  return (
    <div className="grid gap-6 lg:grid-cols-[1fr_340px] lg:items-start">
      <div className="space-y-3">
        {/*
         * Surfaced before the line items, not inside them: a shopper heading for checkout needs to
         * know something changed before they scroll past it, and fixing everything at once beats
         * discovering it one 409 at a time at the payment step.
         */}
        {hasIssues ? (
          <Card className="flex flex-wrap items-center gap-3 border-warning-500/40 bg-warning-500/10 p-4">
            <AlertTriangle className="size-5 shrink-0 text-yellow-700" aria-hidden />
            <p className="flex-1 text-sm">{labels.reviewIssues}</p>
            <Button
              size="sm"
              variant="outline"
              loading={reconcile.isPending}
              onClick={() => reconcile.mutate()}
            >
              {labels.fixIssues}
            </Button>
          </Card>
        ) : null}

        {cart.items.map((item) => (
          <Card key={item.id} className="flex gap-4 p-4">
            <Link
              href={
                item.slug
                  ? { pathname: '/products/[slug]', params: { slug: item.slug } }
                  : '/products'
              }
              className="relative size-24 shrink-0 overflow-hidden rounded-md bg-[var(--muted)]"
            >
              {item.imageUrl ? (
                <Image
                  src={item.imageUrl}
                  alt={item.name ?? ''}
                  fill
                  sizes="96px"
                  className="object-cover"
                />
              ) : null}
            </Link>

            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <Link
                href={
                  item.slug
                    ? { pathname: '/products/[slug]', params: { slug: item.slug } }
                    : '/products'
                }
                className="line-clamp-2 text-sm font-medium hover:text-brand-700"
              >
                {item.name}
              </Link>

              {item.variantLabel ? (
                <p className="text-xs text-[var(--muted-foreground)]">{item.variantLabel}</p>
              ) : null}

              {!item.inStock ? (
                <p className="text-xs font-medium text-danger-500">{labels.itemUnavailable}</p>
              ) : null}

              {item.priceChanged ? (
                <p className="numeric text-xs font-medium text-sale-600">
                  {formatMoney(item.currentPrice, locale)}
                </p>
              ) : null}

              <div className="mt-auto flex flex-wrap items-center gap-3">
                <div className="flex items-center rounded-[var(--radius-control)] border border-[var(--border)]">
                  <button
                    type="button"
                    onClick={() =>
                      updateQuantity.mutate({ itemId: item.id, quantity: item.quantity - 1 })
                    }
                    disabled={updateQuantity.isPending}
                    className="flex size-9 items-center justify-center disabled:opacity-40"
                    aria-label="−"
                  >
                    <Minus className="size-3.5" aria-hidden />
                  </button>

                  <span className="numeric w-9 text-center text-sm">{item.quantity}</span>

                  <button
                    type="button"
                    onClick={() =>
                      updateQuantity.mutate({ itemId: item.id, quantity: item.quantity + 1 })
                    }
                    // Capped at what is available, so the stepper cannot request more than exists.
                    disabled={updateQuantity.isPending || item.quantity >= item.availableQuantity}
                    className="flex size-9 items-center justify-center disabled:opacity-40"
                    aria-label="+"
                  >
                    <Plus className="size-3.5" aria-hidden />
                  </button>
                </div>

                <button
                  type="button"
                  onClick={() => removeItem.mutate(item.id)}
                  disabled={removeItem.isPending}
                  className="inline-flex items-center gap-1 text-xs text-danger-500 hover:underline"
                >
                  <Trash2 className="size-3.5" aria-hidden />
                  {labels.remove}
                </button>
              </div>
            </div>

            <div className="numeric shrink-0 text-end text-sm font-semibold">
              {formatMoney(item.lineTotal, locale)}
            </div>
          </Card>
        ))}
      </div>

      {/* Summary - sticky on desktop so the checkout button stays reachable in a long cart. */}
      <Card className="lg:sticky lg:top-[calc(var(--spacing-header)+1rem)]">
        <div className="space-y-3 p-5">
          {cart.coupon ? (
            <div className="flex items-center justify-between gap-2 rounded-md bg-success-500/10 px-3 py-2">
              <span className="numeric text-sm font-medium">{cart.coupon.code}</span>
              <button
                type="button"
                onClick={() => removeCoupon.mutate()}
                className="text-xs text-danger-500 hover:underline"
              >
                {labels.removeCoupon}
              </button>
            </div>
          ) : (
            <form
              onSubmit={(event) => {
                event.preventDefault();
                if (couponInput.trim()) applyCoupon.mutate(couponInput.trim());
              }}
              className="flex gap-2"
            >
              <Input
                value={couponInput}
                onChange={(event) => setCouponInput(event.target.value.toUpperCase())}
                placeholder={labels.couponPlaceholder}
                aria-label={labels.couponCode}
                invalid={Boolean(couponError)}
                className="h-10"
              />
              <Button type="submit" variant="outline" size="sm" loading={applyCoupon.isPending}>
                {labels.applyCoupon}
              </Button>
            </form>
          )}

          {couponError ? (
            <p role="alert" className="text-xs text-danger-500">
              {couponError}
            </p>
          ) : null}

          <Separator />

          <Row label={labels.subtotal} value={formatMoney(cart.totals.subtotal, locale)} />

          {Number.parseFloat(cart.totals.discount.amount) > 0 ? (
            <Row
              label={labels.discount}
              value={`−${formatMoney(cart.totals.discount, locale)}`}
              tone="success"
            />
          ) : null}

          <Row label={labels.tax} value={formatMoney(cart.totals.tax, locale)} />

          {/*
           * Shipping is not shown as a number here - it depends on a destination the cart does not
           * have. Showing "0.00" would read as free delivery and make the checkout total look like
           * a surprise increase.
           */}
          <Row label={labels.shipping} value={labels.shippingAtCheckout} muted />

          <Separator />

          <div className="flex items-baseline justify-between gap-4">
            <span className="font-semibold">{labels.total}</span>
            <span className="numeric text-lg font-bold">
              {formatMoney(cart.totals.grandTotal, locale)}
            </span>
          </div>

          {belowMinimum ? (
            <>
              <p role="status" className="text-xs text-[var(--danger)]">
                {labels.belowMinimum.replace(
                  '{amount}',
                  formatMoney(
                    { amount: minimumOrder.toFixed(2), currency: cart.totals.subtotal.currency },
                    locale,
                  ),
                )}
              </p>
              <Button size="lg" fullWidth className="mt-2" disabled>
                {labels.checkout}
              </Button>
            </>
          ) : (
            <Button asChild size="lg" fullWidth className="mt-2">
              <Link href="/checkout">{labels.checkout}</Link>
            </Button>
          )}
        </div>
      </Card>
    </div>
  );
}

function Row({
  label,
  value,
  tone,
  muted,
}: {
  label: string;
  value: string;
  tone?: 'success';
  muted?: boolean;
}) {
  return (
    <div className="flex items-baseline justify-between gap-4 text-sm">
      <span className="text-[var(--muted-foreground)]">{label}</span>
      <span
        className={
          tone === 'success'
            ? 'numeric font-medium text-success-600'
            : muted
              ? 'text-xs text-[var(--muted-foreground)]'
              : 'numeric font-medium'
        }
      >
        {value}
      </span>
    </div>
  );
}
