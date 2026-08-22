'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Package, Plus } from 'lucide-react';
import Image from 'next/image';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';
import type { ProductStatus } from '@eshop/api-client';
import { adminApi } from '@/lib/api';
import {
  Badge,
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
import { ProductStatusBadge, humanise } from '@/components/ui/status';
import { hasPermission } from '@/stores/auth-store';
import { formatMoney } from '@/lib/utils';

const STATUSES: ProductStatus[] = ['DRAFT', 'ACTIVE', 'ARCHIVED'];

export default function ProductsPage() {
  return (
    <Suspense fallback={<Skeleton className="h-96 w-full" />}>
      <ProductsList />
    </Suspense>
  );
}

/**
 * The catalog.
 *
 * Selection drives a bulk status change - publishing twenty products one at a time is the single
 * most tedious thing in a console like this, and the endpoint already supports doing it in one call.
 */
function ProductsList() {
  const router = useRouter();
  const params = useSearchParams();
  const queryClient = useQueryClient();

  const q = params.get('q') ?? '';
  const status = (params.get('status') as ProductStatus | null) ?? undefined;
  const page = Number(params.get('page') ?? 0);

  const [search, setSearch] = useState(q);
  const [selected, setSelected] = useState<Set<string>>(new Set());

  const canWrite = hasPermission('product:write');

  const products = useQuery({
    queryKey: ['admin', 'products', { q, status, page }],
    queryFn: () => adminApi().products.list({ q: q || undefined, status, page, size: 20 }),
    placeholderData: keepPreviousData,
  });

  const bulkStatus = useMutation({
    mutationFn: (next: ProductStatus) =>
      adminApi().products.bulkStatus([...selected], next),
    onSuccess: () => {
      setSelected(new Set());
      void queryClient.invalidateQueries({ queryKey: ['admin', 'products'] });
    },
  });

  function setParam(changes: Record<string, string | undefined>) {
    const next = new URLSearchParams(params.toString());
    for (const [key, value] of Object.entries(changes)) {
      if (!value) next.delete(key);
      else next.set(key, value);
    }
    if (!('page' in changes)) next.delete('page');
    router.replace(`/products?${next.toString()}`);
  }

  const rows = products.data?.content ?? [];
  const allSelected = rows.length > 0 && rows.every((row) => selected.has(row.id));

  return (
    <div className="mx-auto max-w-7xl space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">Products</h1>

        {canWrite ? (
          <Button asChild>
            <Link href="/products/new">
              <Plus aria-hidden />
              New product
            </Link>
          </Button>
        ) : null}
      </div>

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
              placeholder="Name or SKU…"
              aria-label="Search products"
            />
          </div>

          <Select
            className="w-40"
            value={status ?? ''}
            onChange={(event) => setParam({ status: event.target.value })}
            aria-label="Status"
          >
            <option value="">All statuses</option>
            {STATUSES.map((value) => (
              <option key={value} value={value}>
                {humanise(value)}
              </option>
            ))}
          </Select>

          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>

        {/* The bulk bar appears only when there is a selection, so it never occupies space idly. */}
        {canWrite && selected.size > 0 ? (
          <div className="flex flex-wrap items-center gap-2 border-b border-[var(--border)] bg-brand-50 p-2 text-xs">
            <span className="numeric font-medium">{selected.size} selected</span>
            {STATUSES.map((value) => (
              <Button
                key={value}
                size="sm"
                variant="outline"
                loading={bulkStatus.isPending}
                onClick={() => bulkStatus.mutate(value)}
              >
                Set {humanise(value).toLowerCase()}
              </Button>
            ))}
            <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
              Clear
            </Button>
          </div>
        ) : null}

        {products.isLoading ? (
          <div className="space-y-2 p-3">
            {Array.from({ length: 8 }, (_, index) => (
              <Skeleton key={index} className="h-12 w-full" />
            ))}
          </div>
        ) : products.isError ? (
          <ErrorState onRetry={() => void products.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyState
            title="No products match these filters"
            hint={q ? 'Try a different search term.' : 'Create your first product to get started.'}
          />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  {canWrite ? (
                    <Th className="w-8">
                      <input
                        type="checkbox"
                        checked={allSelected}
                        onChange={(event) =>
                          setSelected(
                            event.target.checked ? new Set(rows.map((row) => row.id)) : new Set(),
                          )
                        }
                        aria-label="Select all on this page"
                      />
                    </Th>
                  ) : null}
                  <Th>Product</Th>
                  <Th>SKU</Th>
                  <Th className="text-end">Price</Th>
                  <Th>Status</Th>
                  <Th>Stock</Th>
                </tr>
              </thead>
              <tbody>
                {rows.map((product) => (
                  <tr key={product.id} className="hover:bg-[var(--muted)]">
                    {canWrite ? (
                      <Td>
                        <input
                          type="checkbox"
                          checked={selected.has(product.id)}
                          onChange={(event) =>
                            setSelected((current) => {
                              const next = new Set(current);
                              if (event.target.checked) next.add(product.id);
                              else next.delete(product.id);
                              return next;
                            })
                          }
                          aria-label={`Select ${product.name}`}
                        />
                      </Td>
                    ) : null}

                    <Td>
                      <div className="flex items-center gap-2.5">
                        <span className="relative flex size-9 shrink-0 items-center justify-center overflow-hidden rounded bg-[var(--muted)]">
                          {product.imageUrl ? (
                            <Image
                              src={product.imageUrl}
                              alt=""
                              fill
                              sizes="36px"
                              className="object-cover"
                              unoptimized
                            />
                          ) : (
                            <Package
                              className="size-4 text-[var(--muted-foreground)]"
                              aria-hidden
                            />
                          )}
                        </span>

                        <div className="min-w-0">
                          <Link
                            href={`/products/${product.id}`}
                            className="line-clamp-1 font-medium hover:text-brand-700"
                          >
                            {product.name}
                          </Link>
                          {product.brandName ? (
                            <p className="text-xs text-[var(--muted-foreground)]">
                              {product.brandName}
                            </p>
                          ) : null}
                        </div>
                      </div>
                    </Td>

                    <Td className="numeric text-xs text-[var(--muted-foreground)]">{product.sku}</Td>

                    <Td className="numeric text-end">
                      {formatMoney(product.price)}
                      {product.compareAtPrice ? (
                        <span className="ms-1 text-xs text-[var(--muted-foreground)] line-through">
                          {formatMoney(product.compareAtPrice)}
                        </span>
                      ) : null}
                    </Td>

                    <Td>
                      <ProductStatusBadge status={product.status} />
                      {product.featured ? (
                        <Badge tone="brand" className="ms-1">
                          Featured
                        </Badge>
                      ) : null}
                    </Td>

                    <Td>
                      {product.available === null || product.available === undefined ? (
                        <span className="text-xs text-[var(--muted-foreground)]">-</span>
                      ) : product.available <= 0 ? (
                        <Badge tone="danger">Out of stock</Badge>
                      ) : (
                        <span className="numeric">{product.available}</span>
                      )}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>

            {products.data ? (
              <Pagination
                page={products.data.page}
                label="products"
                onPageChange={(next) => setParam({ page: String(next) })}
              />
            ) : null}
          </>
        )}
      </Card>
    </div>
  );
}
