'use client';

import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, ArrowDownRight, ArrowUpRight, Package, SearchX } from 'lucide-react';
import Link from 'next/link';
import { useState } from 'react';
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import type { CountMetric, Metric } from '@eshop/api-client';
import { adminApi } from '@/lib/api';
import {
  Badge,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  EmptyState,
  ErrorState,
  Select,
  Skeleton,
  Table,
  Td,
  Th,
} from '@/components/ui/primitives';
import { OrderStatusBadge } from '@/components/ui/status';
import {
  cn,
  formatChange,
  formatDate,
  formatMoney,
  formatMoneyCompact,
  formatNumber,
  formatRelative,
} from '@/lib/utils';

const RANGES = [
  { days: 7, label: 'Last 7 days' },
  { days: 30, label: 'Last 30 days' },
  { days: 90, label: 'Last 90 days' },
];

/**
 * The operations dashboard.
 *
 * Two halves, deliberately. The top is *trends* — figures against the equivalent preceding period,
 * which is the only way an absolute number means anything. The bottom is *queues*: things that need
 * a person today. An operator opening this at 9am should be able to tell within a second whether
 * anything is on fire.
 */
export default function DashboardPage() {
  const [rangeDays, setRangeDays] = useState(30);

  const summary = useQuery({
    queryKey: ['dashboard', 'summary', rangeDays],
    queryFn: () => adminApi().dashboard.summary(rangeDays),
  });

  const series = useQuery({
    queryKey: ['dashboard', 'revenue', rangeDays],
    queryFn: () => adminApi().dashboard.revenueSeries(rangeDays),
  });

  const recent = useQuery({
    queryKey: ['dashboard', 'recent-orders'],
    queryFn: () => adminApi().dashboard.recentOrders(8),
  });

  const lowStock = useQuery({
    queryKey: ['dashboard', 'low-stock'],
    queryFn: () => adminApi().dashboard.lowStock(8),
  });

  const topProducts = useQuery({
    queryKey: ['dashboard', 'top-products', rangeDays],
    queryFn: () => adminApi().dashboard.topProducts(rangeDays, 8),
  });

  const zeroResults = useQuery({
    queryKey: ['dashboard', 'zero-results', rangeDays],
    queryFn: () => adminApi().dashboard.zeroResultSearches(rangeDays, 8),
  });

  return (
    <div className="mx-auto max-w-7xl space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">Dashboard</h1>

        <Select
          className="w-40"
          value={rangeDays}
          onChange={(event) => setRangeDays(Number(event.target.value))}
          aria-label="Reporting period"
        >
          {RANGES.map((range) => (
            <option key={range.days} value={range.days}>
              {range.label}
            </option>
          ))}
        </Select>
      </div>

      {/* ---- trends ---- */}
      {summary.isError ? (
        <Card>
          <ErrorState onRetry={() => void summary.refetch()} />
        </Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <MetricTile
            label="Revenue"
            metric={summary.data?.revenue}
            loading={summary.isLoading}
            format={formatMoney}
          />
          <MetricTile
            label="Orders"
            metric={summary.data?.orders}
            loading={summary.isLoading}
            format={formatNumber}
          />
          <MetricTile
            label="Average order value"
            metric={summary.data?.averageOrderValue}
            loading={summary.isLoading}
            format={formatMoney}
          />
          <MetricTile
            label="New customers"
            metric={summary.data?.newCustomers}
            loading={summary.isLoading}
            format={formatNumber}
          />
        </div>
      )}

      {/* ---- queues: things that need a person ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <QueueTile
          label="Awaiting payment"
          value={summary.data?.awaitingPayment}
          href="/orders?status=PENDING_PAYMENT"
          loading={summary.isLoading}
        />
        <QueueTile
          label="To fulfil"
          value={summary.data?.awaitingFulfilment}
          href="/orders?status=PAID"
          loading={summary.isLoading}
          urgent
        />
        <QueueTile
          label="Failed payments"
          value={summary.data?.failedPayments}
          href="/orders?paymentStatus=FAILED"
          loading={summary.isLoading}
          urgent
        />
        <QueueTile
          label="Reviews to moderate"
          value={summary.data?.pendingReviews}
          href="/reviews?status=PENDING"
          loading={summary.isLoading}
        />
        <QueueTile
          label="Out of stock"
          value={summary.data?.outOfStockSkus}
          href="/inventory?lowStockOnly=true"
          loading={summary.isLoading}
          urgent
        />
      </div>

      {/* ---- revenue ---- */}
      <Card>
        <CardHeader>
          <CardTitle>Revenue</CardTitle>
        </CardHeader>
        <CardBody>
          {series.isLoading ? (
            <Skeleton className="h-64 w-full" />
          ) : series.isError ? (
            <ErrorState onRetry={() => void series.refetch()} />
          ) : (series.data?.length ?? 0) === 0 ? (
            <EmptyState title="No revenue in this period" />
          ) : (
            <RevenueChart
              points={(series.data ?? []).map((point) => ({
                at: point.at,
                revenue: Number(point.revenue.amount),
                currency: point.revenue.currency,
                orders: point.orders,
              }))}
            />
          )}
        </CardBody>
      </Card>

      <div className="grid gap-4 lg:grid-cols-2">
        {/* ---- recent orders ---- */}
        <Card>
          <CardHeader className="flex items-center justify-between">
            <CardTitle>Recent orders</CardTitle>
            <Link href="/orders" className="text-xs text-brand-700 hover:underline">
              View all
            </Link>
          </CardHeader>

          {recent.isLoading ? (
            <CardBody>
              <Skeleton className="h-40 w-full" />
            </CardBody>
          ) : recent.isError ? (
            <ErrorState onRetry={() => void recent.refetch()} />
          ) : (recent.data?.content.length ?? 0) === 0 ? (
            <EmptyState title="No orders yet" />
          ) : (
            <Table>
              <tbody>
                {recent.data?.content.map((order) => (
                  <tr key={order.id} className="hover:bg-[var(--muted)]">
                    <Td>
                      <Link
                        href={`/orders?q=${order.orderNumber}`}
                        className="numeric font-medium hover:text-brand-700"
                      >
                        {order.orderNumber}
                      </Link>
                      <p className="text-xs text-[var(--muted-foreground)]">
                        {formatRelative(order.placedAt)}
                      </p>
                    </Td>
                    <Td>
                      <OrderStatusBadge status={order.status} />
                    </Td>
                    <Td className="numeric text-end font-medium">
                      {formatMoney(order.grandTotal)}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        {/* ---- low stock ---- */}
        <Card>
          <CardHeader className="flex items-center justify-between">
            <CardTitle>Low stock</CardTitle>
            <Link href="/inventory?lowStockOnly=true" className="text-xs text-brand-700 hover:underline">
              View all
            </Link>
          </CardHeader>

          {lowStock.isLoading ? (
            <CardBody>
              <Skeleton className="h-40 w-full" />
            </CardBody>
          ) : lowStock.isError ? (
            <ErrorState onRetry={() => void lowStock.refetch()} />
          ) : (lowStock.data?.length ?? 0) === 0 ? (
            <EmptyState title="Nothing below its reorder level" hint="Stock levels are healthy." />
          ) : (
            <Table>
              <tbody>
                {lowStock.data?.map((item) => (
                  <tr key={item.inventoryItemId} className="hover:bg-[var(--muted)]">
                    <Td>
                      <p className="line-clamp-1 font-medium">{item.productName}</p>
                      <p className="numeric text-xs text-[var(--muted-foreground)]">{item.sku}</p>
                    </Td>
                    <Td className="text-end">
                      <Badge tone={item.available <= 0 ? 'danger' : 'warning'}>
                        {item.available <= 0 ? 'Out of stock' : `${item.available} left`}
                      </Badge>
                      <p className="numeric mt-0.5 text-xs text-[var(--muted-foreground)]">
                        reorder at {item.reorderLevel}
                      </p>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        {/* ---- top products ---- */}
        <Card>
          <CardHeader>
            <CardTitle>Best sellers</CardTitle>
          </CardHeader>

          {topProducts.isLoading ? (
            <CardBody>
              <Skeleton className="h-40 w-full" />
            </CardBody>
          ) : (topProducts.data?.length ?? 0) === 0 ? (
            <EmptyState title="No sales in this period" />
          ) : (
            <Table>
              <thead>
                <tr>
                  <Th>Product</Th>
                  <Th className="text-end">Units</Th>
                  <Th className="text-end">Revenue</Th>
                </tr>
              </thead>
              <tbody>
                {topProducts.data?.map((product) => (
                  <tr key={product.productId} className="hover:bg-[var(--muted)]">
                    <Td className="flex items-center gap-2">
                      <span className="flex size-8 shrink-0 items-center justify-center rounded bg-[var(--muted)]">
                        <Package className="size-4 text-[var(--muted-foreground)]" aria-hidden />
                      </span>
                      <Link
                        href={`/products/${product.productId}`}
                        className="line-clamp-1 hover:text-brand-700"
                      >
                        {product.name}
                      </Link>
                    </Td>
                    <Td className="numeric text-end">{formatNumber(product.unitsSold)}</Td>
                    <Td className="numeric text-end font-medium">
                      {formatMoney(product.revenue)}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        {/* ---- zero-result searches: the shortest path to a catalog gap ---- */}
        <Card>
          <CardHeader>
            <CardTitle>Searches with no results</CardTitle>
          </CardHeader>

          {zeroResults.isLoading ? (
            <CardBody>
              <Skeleton className="h-40 w-full" />
            </CardBody>
          ) : (zeroResults.data?.length ?? 0) === 0 ? (
            <EmptyState
              title="Every search found something"
              hint="Customers are finding what they look for."
            />
          ) : (
            <>
              <CardBody className="pb-0">
                <p className="flex items-start gap-2 text-xs text-[var(--muted-foreground)]">
                  <SearchX className="mt-0.5 size-4 shrink-0" aria-hidden />
                  Customers looked for these and found nothing — each one is either a gap in the
                  catalog or a missing synonym.
                </p>
              </CardBody>
              <Table className="mt-2">
                <tbody>
                  {zeroResults.data?.map((row) => (
                    <tr key={row.query} className="hover:bg-[var(--muted)]">
                      <Td>{row.query}</Td>
                      <Td className="numeric text-end text-[var(--muted-foreground)]">
                        {formatNumber(row.searches)} searches
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </>
          )}
        </Card>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ components --

/** A headline figure with its change against the preceding period of the same length. */
function MetricTile({
  label,
  metric,
  loading,
  format,
}: {
  label: string;
  metric: Metric | CountMetric | undefined;
  loading: boolean;
  format: (value: never) => string;
}) {
  const change = formatChange(metric?.changePercent);
  const up = (metric?.changePercent ?? 0) > 0;
  const flat = (metric?.changePercent ?? 0) === 0;

  return (
    <Card className="p-4">
      <p className="text-xs text-[var(--muted-foreground)]">{label}</p>

      {loading ? (
        <Skeleton className="mt-2 h-7 w-24" />
      ) : (
        <p className="numeric mt-1 text-2xl font-semibold">
          {metric ? format(metric.current as never) : '—'}
        </p>
      )}

      {change && !flat ? (
        <p
          className={cn(
            'mt-1 flex items-center gap-1 text-xs',
            up ? 'text-success-500' : 'text-danger-500',
          )}
        >
          {up ? (
            <ArrowUpRight className="size-3.5" aria-hidden />
          ) : (
            <ArrowDownRight className="size-3.5" aria-hidden />
          )}
          <span className="numeric">{change}</span>
          <span className="text-[var(--muted-foreground)]">vs previous period</span>
        </p>
      ) : null}
    </Card>
  );
}

/**
 * A count that is also a link to the work.
 *
 * `urgent` turns the tile red only when the count is non-zero — a permanently red zero teaches
 * operators to ignore the colour.
 */
function QueueTile({
  label,
  value,
  href,
  loading,
  urgent = false,
}: {
  label: string;
  value: number | undefined;
  href: string;
  loading: boolean;
  urgent?: boolean;
}) {
  const active = (value ?? 0) > 0;

  return (
    <Link
      href={href}
      className={cn(
        'rounded-lg border p-3 transition-colors',
        active && urgent
          ? 'border-danger-500/30 bg-danger-500/5 hover:bg-danger-500/10'
          : 'border-[var(--border)] bg-[var(--card)] hover:bg-[var(--muted)]',
      )}
    >
      <p className="flex items-center gap-1.5 text-xs text-[var(--muted-foreground)]">
        {active && urgent ? (
          <AlertTriangle className="size-3.5 text-danger-500" aria-hidden />
        ) : null}
        {label}
      </p>
      {loading ? (
        <Skeleton className="mt-1.5 h-6 w-10" />
      ) : (
        <p className="numeric mt-0.5 text-xl font-semibold">{formatNumber(value ?? 0)}</p>
      )}
    </Link>
  );
}

interface ChartPoint {
  at: string;
  revenue: number;
  currency: string;
  orders: number;
}

function RevenueChart({ points }: { points: ChartPoint[] }) {
  const currency = points[0]?.currency ?? 'EGP';

  return (
    <ResponsiveContainer width="100%" height={260}>
      <AreaChart data={points} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
        <defs>
          <linearGradient id="revenueFill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--color-brand-500)" stopOpacity={0.28} />
            <stop offset="100%" stopColor="var(--color-brand-500)" stopOpacity={0} />
          </linearGradient>
        </defs>

        {/* Horizontal only: vertical lines add ink without helping anyone read a value. */}
        <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" />

        <XAxis
          dataKey="at"
          tickFormatter={(value: string) => formatDate(value)}
          tick={{ fontSize: 11 }}
          stroke="var(--muted-foreground)"
          tickLine={false}
          axisLine={false}
          minTickGap={24}
        />
        <YAxis
          tickFormatter={(value: number) =>
            formatMoneyCompact({ amount: String(value), currency })
          }
          tick={{ fontSize: 11 }}
          stroke="var(--muted-foreground)"
          tickLine={false}
          axisLine={false}
          width={64}
        />

        <Tooltip
          contentStyle={{
            background: 'var(--card)',
            border: '1px solid var(--border)',
            borderRadius: 8,
            fontSize: 12,
          }}
          labelFormatter={(value: string) => formatDate(value)}
          formatter={(value: number, name: string) =>
            name === 'revenue'
              ? [formatMoney({ amount: String(value), currency }), 'Revenue']
              : [formatNumber(value), 'Orders']
          }
        />

        <Area
          type="monotone"
          dataKey="revenue"
          stroke="var(--color-brand-600)"
          strokeWidth={2}
          fill="url(#revenueFill)"
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}
