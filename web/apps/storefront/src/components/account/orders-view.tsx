'use client';

import { useInfiniteQuery } from '@tanstack/react-query';
import { useTranslations } from 'next-intl';
import { Package } from 'lucide-react';
import Image from 'next/image';
import { formatMoney, type OrderStatus } from '@eshop/api-client';
import { Link } from '@/i18n/routing';
import { Button } from '@/components/ui/button';
import { Badge, Card, Skeleton } from '@/components/ui/primitives';
import { browserApi } from '@/lib/api-browser';
import { formatRelativeTime } from '@/lib/utils';

/**
 * Order history.
 *
 * Colour carries meaning here, so it is assigned by outcome rather than by taste: green for
 * delivered, red for cancelled or failed, amber for anything still waiting on the customer, neutral
 * for in-flight. A shopper scanning the list should be able to spot the one that needs attention
 * without reading every row.
 */
const STATUS_TONE: Record<OrderStatus, 'neutral' | 'success' | 'warning' | 'danger' | 'brand'> = {
  PENDING_PAYMENT: 'warning',
  PAID: 'brand',
  PROCESSING: 'brand',
  SHIPPED: 'brand',
  DELIVERED: 'success',
  CANCELLED: 'danger',
  REFUNDED: 'neutral',
  PARTIALLY_REFUNDED: 'neutral',
  FAILED: 'danger',
};

export function OrdersView({ locale }: { locale: string }) {
  /*
   * Read here rather than passed down from the page.
   *
   * `item` is pluralised and `status` is a lookup - both are functions, and a server component
   * cannot hand a function to a client component. next-intl's client hook works during prerender
   * because the locale layout puts the messages in context.
   */
  const t = useTranslations('order');
  const cart = useTranslations('cart');
  const common = useTranslations('common');

  const { data, isLoading, isError, refetch, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useInfiniteQuery({
      queryKey: ['orders'],
      queryFn: ({ pageParam }) => browserApi().orders.list(pageParam, 10),
      initialPageParam: 0,
      // Paged rather than loaded all at once: a long-standing customer can have hundreds of orders,
      // and the first ten are the ones they came for.
      getNextPageParam: (lastPage) => (lastPage.page.last ? undefined : lastPage.page.number + 1),
    });

  if (isLoading) {
    return (
      <div className="space-y-3">
        {Array.from({ length: 3 }, (_, index) => (
          <Skeleton key={index} className="h-28 w-full" />
        ))}
      </div>
    );
  }

  if (isError) {
    return (
      <Card className="p-8 text-center">
        <p className="font-medium">{common('somethingWentWrong')}</p>
        <Button variant="outline" className="mt-4" onClick={() => void refetch()}>
          {common('retry')}
        </Button>
      </Card>
    );
  }

  const orders = data?.pages.flatMap((page) => page.content) ?? [];

  if (orders.length === 0) {
    return (
      <Card className="flex flex-col items-center gap-3 p-12 text-center">
        <Package className="size-12 text-[var(--muted-foreground)]" aria-hidden />
        <p className="text-lg font-medium">{t('noOrders')}</p>
        <p className="text-sm text-[var(--muted-foreground)]">{t('noOrdersHint')}</p>
        <Button asChild className="mt-2">
          <Link href="/products">{cart('continueShopping')}</Link>
        </Button>
      </Card>
    );
  }

  return (
    <div className="space-y-3">
      {orders.map((order) => (
        <Card key={order.id} className="p-4">
          <Link
            href={{
              pathname: '/account/orders/[orderNumber]',
              params: { orderNumber: order.orderNumber },
            }}
            className="flex gap-4"
          >
            <div className="relative size-20 shrink-0 overflow-hidden rounded-md bg-[var(--muted)]">
              {order.previewImageUrl ? (
                <Image
                  src={order.previewImageUrl}
                  alt=""
                  fill
                  sizes="80px"
                  className="object-cover"
                />
              ) : null}
            </div>

            <div className="flex min-w-0 flex-1 flex-col gap-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="numeric text-sm font-semibold">{order.orderNumber}</span>
                <Badge tone={STATUS_TONE[order.status]}>{t(`status.${order.status}`)}</Badge>
              </div>

              {order.previewName ? (
                <p className="line-clamp-1 text-sm text-[var(--muted-foreground)]">
                  {order.previewName}
                </p>
              ) : null}

              <p className="text-xs text-[var(--muted-foreground)]">
                {cart('item', { count: order.itemCount })} ·{' '}
                {formatRelativeTime(order.placedAt, locale)}
              </p>
            </div>

            <span className="numeric shrink-0 self-center font-semibold">
              {formatMoney(order.grandTotal, locale)}
            </span>
          </Link>
        </Card>
      ))}

      {hasNextPage ? (
        <Button
          variant="outline"
          fullWidth
          loading={isFetchingNextPage}
          onClick={() => void fetchNextPage()}
        >
          {common('showMore')}
        </Button>
      ) : null}
    </div>
  );
}
