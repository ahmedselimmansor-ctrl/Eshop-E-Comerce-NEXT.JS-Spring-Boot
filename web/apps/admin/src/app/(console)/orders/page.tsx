'use client';

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';
import type { OrderStatus, PaymentStatus } from '@eshop/api-client';
import { adminApi } from '@/lib/api';
import {
  Button,
  Card,
  EmptyState,
  ErrorState,
  Input,
  Select,
  Skeleton,
  Table,
  Td,
  Th,
} from '@/components/ui/primitives';
import { Pagination } from '@/components/ui/pagination';
import { OrderStatusBadge, PaymentStatusBadge, humanise } from '@/components/ui/status';
import { formatMoney, formatRelative } from '@/lib/utils';

const ORDER_STATUSES: OrderStatus[] = [
  'PENDING_PAYMENT',
  'PAID',
  'PROCESSING',
  'SHIPPED',
  'DELIVERED',
  'CANCELLED',
  'REFUNDED',
  'PARTIALLY_REFUNDED',
  'FAILED',
];

const PAYMENT_STATUSES: PaymentStatus[] = [
  'PENDING',
  'AUTHORIZED',
  'PAID',
  'FAILED',
  'REFUNDED',
  'PARTIALLY_REFUNDED',
];

export default function OrdersPage() {
  return (
    <Suspense fallback={<Skeleton className="h-96 w-full" />}>
      <OrdersList />
    </Suspense>
  );
}

/**
 * The order queue.
 *
 * Filters live in the URL rather than in component state, so "orders awaiting fulfilment" is a link
 * an operator can bookmark or paste into a ticket - which is exactly how the dashboard's queue tiles
 * reach this screen.
 */
function OrdersList() {
  const router = useRouter();
  const params = useSearchParams();

  const status = (params.get('status') as OrderStatus | null) ?? undefined;
  const paymentStatus = (params.get('paymentStatus') as PaymentStatus | null) ?? undefined;
  const q = params.get('q') ?? '';
  const page = Number(params.get('page') ?? 0);

  // Held locally so typing does not fire a request per keystroke; committed on submit.
  const [search, setSearch] = useState(q);

  const orders = useQuery({
    queryKey: ['admin', 'orders', { q, status, paymentStatus, page }],
    queryFn: () =>
      adminApi().orders.list({ q: q || undefined, status, paymentStatus, page, size: 20 }),
    // Keeps the previous page on screen while the next one loads, so paging does not flash empty.
    placeholderData: keepPreviousData,
  });

  function setParam(changes: Record<string, string | undefined>) {
    const next = new URLSearchParams(params.toString());
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined || value === '') next.delete(key);
      else next.set(key, value);
    }
    // Any filter change invalidates the current page number.
    if (!('page' in changes)) next.delete('page');
    router.replace(`/orders?${next.toString()}`);
  }

  return (
    <div className="mx-auto max-w-7xl space-y-4">
      <h1 className="text-xl font-semibold">Orders</h1>

      <Card>
        <form
          className="flex flex-wrap items-end gap-2 border-b border-[var(--border)] p-3"
          onSubmit={(event) => {
            event.preventDefault();
            setParam({ q: search.trim() });
          }}
        >
          <div className="min-w-48 flex-1">
            <Input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Order number, email…"
              aria-label="Search orders"
            />
          </div>

          <Select
            className="w-44"
            value={status ?? ''}
            onChange={(event) => setParam({ status: event.target.value })}
            aria-label="Order status"
          >
            <option value="">All statuses</option>
            {ORDER_STATUSES.map((value) => (
              <option key={value} value={value}>
                {humanise(value)}
              </option>
            ))}
          </Select>

          <Select
            className="w-44"
            value={paymentStatus ?? ''}
            onChange={(event) => setParam({ paymentStatus: event.target.value })}
            aria-label="Payment status"
          >
            <option value="">All payments</option>
            {PAYMENT_STATUSES.map((value) => (
              <option key={value} value={value}>
                {humanise(value)}
              </option>
            ))}
          </Select>

          <Button type="submit" variant="outline">
            Search
          </Button>

          {q || status || paymentStatus ? (
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                setSearch('');
                router.replace('/orders');
              }}
            >
              Clear
            </Button>
          ) : null}
        </form>

        {orders.isLoading ? (
          <div className="space-y-2 p-3">
            {Array.from({ length: 8 }, (_, index) => (
              <Skeleton key={index} className="h-10 w-full" />
            ))}
          </div>
        ) : orders.isError ? (
          <ErrorState onRetry={() => void orders.refetch()} />
        ) : orders.data?.content.length === 0 ? (
          <EmptyState
            title="No orders match these filters"
            hint="Try clearing the search or widening the status filter."
          />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>Order</Th>
                  <Th>Placed</Th>
                  <Th>Status</Th>
                  <Th>Payment</Th>
                  <Th className="text-end">Items</Th>
                  <Th className="text-end">Total</Th>
                </tr>
              </thead>
              <tbody>
                {orders.data?.content.map((order) => (
                  <tr key={order.id} className="hover:bg-[var(--muted)]">
                    <Td>
                      <Link
                        href={`/orders/${order.id}`}
                        className="numeric font-medium hover:text-brand-700"
                      >
                        {order.orderNumber}
                      </Link>
                      {order.previewName ? (
                        <p className="line-clamp-1 text-xs text-[var(--muted-foreground)]">
                          {order.previewName}
                        </p>
                      ) : null}
                    </Td>
                    <Td className="whitespace-nowrap text-[var(--muted-foreground)]">
                      {formatRelative(order.placedAt)}
                    </Td>
                    <Td>
                      <OrderStatusBadge status={order.status} />
                    </Td>
                    <Td>
                      <PaymentStatusBadge status={order.paymentStatus as PaymentStatus} />
                    </Td>
                    <Td className="numeric text-end">{order.itemCount}</Td>
                    <Td className="numeric text-end font-medium">
                      {formatMoney(order.grandTotal)}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>

            {orders.data ? (
              <Pagination
                page={orders.data.page}
                label="orders"
                onPageChange={(next) => setParam({ page: String(next) })}
              />
            ) : null}
          </>
        )}
      </Card>
    </div>
  );
}
