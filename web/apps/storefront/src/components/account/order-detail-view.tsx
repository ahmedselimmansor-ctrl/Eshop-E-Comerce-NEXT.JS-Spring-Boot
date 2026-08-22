'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Check, Truck } from 'lucide-react';
import Image from 'next/image';
import { useState } from 'react';
import { ApiError, formatMoney } from '@eshop/api-client';
import { Link } from '@/i18n/routing';
import { Button } from '@/components/ui/button';
import { Badge, Card, Separator, Skeleton, Textarea } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { formatRelativeTime } from '@/lib/utils';

export function OrderDetailView({
  orderNumber,
  locale,
}: {
  orderNumber: string;
  locale: string;
}) {
  // Status is a lookup keyed by the order's state, which makes it a function — and functions do not
  // cross the server/client boundary. Reading the messages here sidesteps that entirely.
  const t = useTranslations('order');
  const cart = useTranslations('cart');
  const checkout = useTranslations('checkout');
  const common = useTranslations('common');

  const queryClient = useQueryClient();
  const [cancelling, setCancelling] = useState(false);
  const [reason, setReason] = useState('');

  const { data: order, isLoading, isError, refetch } = useQuery({
    queryKey: ['order', orderNumber],
    queryFn: () => browserApi().orders.detail(orderNumber),
  });

  const cancelOrder = useMutation({
    mutationFn: () => browserApi().orders.cancel(orderNumber, reason),
    onSuccess: (updated) => {
      queryClient.setQueryData(['order', orderNumber], updated);
      // The list shows a status badge, so it is now stale too.
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
      setCancelling(false);
    },
  });

  if (isLoading) return <Skeleton className="h-96 w-full" />;

  if (isError || !order) {
    return (
      <Card className="p-8 text-center">
        <p className="font-medium">{common('somethingWentWrong')}</p>
        <Button variant="outline" className="mt-4" onClick={() => void refetch()}>
          {common('retry')}
        </Button>
      </Card>
    );
  }

  const address = order.shippingAddress as Record<string, string | undefined>;
  const cancelError = cancelOrder.error instanceof ApiError ? cancelOrder.error.detail : null;

  return (
    <div className="space-y-4">
      <Card className="p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="numeric text-lg font-bold">{order.orderNumber}</h2>
            <p className="text-xs text-[var(--muted-foreground)]">
              {t('orderDate')}: {formatRelativeTime(order.placedAt, locale)}
            </p>
          </div>
          <Badge tone={order.status === 'DELIVERED' ? 'success' : 'brand'}>
            {t(`status.${order.status}`)}
          </Badge>
        </div>

        {/* Cancellation is only offered while the API would actually allow it — the `cancellable`
            flag comes from the order state machine, so the button never appears for an order that
            has already shipped. */}
        {order.cancellable ? (
          <div className="mt-4">
            {cancelling ? (
              <div className="space-y-3 rounded-[var(--radius-control)] border border-[var(--border)] p-4">
                <p className="text-sm font-medium">{t('cancelConfirm')}</p>
                <Textarea
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  placeholder={t('cancelReason')}
                  maxLength={500}
                  aria-label={t('cancelReason')}
                />
                {cancelError ? (
                  <p role="alert" className="text-sm text-danger-500">
                    {cancelError}
                  </p>
                ) : null}
                <div className="flex gap-2">
                  <Button variant="outline" size="sm" onClick={() => setCancelling(false)}>
                    {common('cancel')}
                  </Button>
                  <Button
                    variant="danger"
                    size="sm"
                    loading={cancelOrder.isPending}
                    onClick={() => cancelOrder.mutate()}
                  >
                    {common('confirm')}
                  </Button>
                </div>
              </div>
            ) : (
              <Button variant="outline" size="sm" onClick={() => setCancelling(true)}>
                {t('cancelOrder')}
              </Button>
            )}
          </div>
        ) : null}
      </Card>

      {/* Items */}
      <Card className="divide-y divide-[var(--border)]">
        {order.items.map((item) => (
          <div key={item.id} className="flex gap-4 p-4">
            <div className="relative size-16 shrink-0 overflow-hidden rounded-md bg-[var(--muted)]">
              {item.imageUrl ? (
                <Image src={item.imageUrl} alt="" fill sizes="64px" className="object-cover" />
              ) : null}
            </div>

            <div className="min-w-0 flex-1">
              {/* Links back to the product when it still exists — an archived product has no slug,
                  and the order line keeps its own frozen copy of the name either way. */}
              {item.slug ? (
                <Link
                  href={{ pathname: '/products/[slug]', params: { slug: item.slug } }}
                  className="line-clamp-2 text-sm font-medium hover:text-brand-700"
                >
                  {item.name}
                </Link>
              ) : (
                <p className="line-clamp-2 text-sm font-medium">{item.name}</p>
              )}

              {item.variantLabel ? (
                <p className="text-xs text-[var(--muted-foreground)]">{item.variantLabel}</p>
              ) : null}

              <p className="numeric mt-1 text-xs text-[var(--muted-foreground)]">
                {formatMoney(item.unitPrice, locale)} × {item.quantity}
              </p>
            </div>

            <span className="numeric shrink-0 text-sm font-semibold">
              {formatMoney(item.lineTotal, locale)}
            </span>
          </div>
        ))}
      </Card>

      {/* Totals */}
      <Card className="space-y-2 p-5">
        <Row label={cart('subtotal')} value={formatMoney(order.totals.subtotal, locale)} />
        {Number.parseFloat(order.totals.discount.amount) > 0 ? (
          <Row label={cart('discount')} value={`−${formatMoney(order.totals.discount, locale)}`} />
        ) : null}
        <Row label={cart('tax')} value={formatMoney(order.totals.tax, locale)} />
        <Row label={cart('shipping')} value={formatMoney(order.totals.shipping, locale)} />
        {Number.parseFloat(order.totals.codFee.amount) > 0 ? (
          <Row label={cart('codFee')} value={formatMoney(order.totals.codFee, locale)} />
        ) : null}
        <Separator />
        <div className="flex items-baseline justify-between">
          <span className="font-semibold">{cart('total')}</span>
          <span className="numeric text-lg font-bold">
            {formatMoney(order.totals.grandTotal, locale)}
          </span>
        </div>
      </Card>

      {/* Address — the frozen snapshot from placement, not the current address book entry. */}
      <Card className="p-5">
        <h3 className="mb-2 font-semibold">{checkout('shippingAddress')}</h3>
        <p className="text-sm">{address.recipientName}</p>
        <p className="text-sm text-[var(--muted-foreground)]">{address.formatted}</p>
        <p className="numeric text-sm text-[var(--muted-foreground)]">{address.phone}</p>

        {order.shippingMethodName ? (
          <p className="mt-3 text-sm">
            <span className="text-[var(--muted-foreground)]">{checkout('shippingMethod')}: </span>
            {order.shippingMethodName}
          </p>
        ) : null}
      </Card>

      {/* Shipments */}
      {order.shipments.length > 0 ? (
        <Card className="space-y-3 p-5">
          {order.shipments.map((shipment) => (
            <div key={shipment.id} className="flex items-start gap-3">
              <Truck className="mt-0.5 size-5 shrink-0 text-brand-600" aria-hidden />
              <div className="text-sm">
                {shipment.carrier ? (
                  <p>
                    <span className="text-[var(--muted-foreground)]">{t('carrier')}: </span>
                    {shipment.carrier}
                  </p>
                ) : null}
                {shipment.trackingNumber ? (
                  <p className="numeric">
                    <span className="text-[var(--muted-foreground)]">
                      {t('trackingNumber')}:{' '}
                    </span>
                    {shipment.trackingUrl ? (
                      <a
                        href={shipment.trackingUrl}
                        target="_blank"
                        rel="noopener noreferrer"
                        className="text-brand-700 hover:underline"
                      >
                        {shipment.trackingNumber}
                      </a>
                    ) : (
                      shipment.trackingNumber
                    )}
                  </p>
                ) : null}
              </div>
            </div>
          ))}
        </Card>
      ) : null}

      {/* Timeline, oldest first — it reads as a progression rather than a log. */}
      <Card className="p-5">
        <h3 className="mb-4 font-semibold">{t('timeline')}</h3>
        <ol className="space-y-4">
          {order.timeline.map((entry, index) => (
            <li key={`${entry.status}-${index}`} className="flex gap-3">
              <div className="flex flex-col items-center">
                <span className="flex size-6 items-center justify-center rounded-full bg-brand-600 text-white">
                  <Check className="size-3.5" aria-hidden />
                </span>
                {index < order.timeline.length - 1 ? (
                  <span className="mt-1 w-px flex-1 bg-[var(--border)]" aria-hidden />
                ) : null}
              </div>

              <div className="pb-1">
                <p className="text-sm font-medium">{t(`status.${entry.status}`)}</p>
                {entry.note ? (
                  <p className="text-xs text-[var(--muted-foreground)]">{entry.note}</p>
                ) : null}
                <p className="text-xs text-[var(--muted-foreground)]">
                  {formatRelativeTime(entry.at, locale)}
                </p>
              </div>
            </li>
          ))}
        </ol>
      </Card>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-4 text-sm">
      <span className="text-[var(--muted-foreground)]">{label}</span>
      <span className="numeric font-medium">{value}</span>
    </div>
  );
}
