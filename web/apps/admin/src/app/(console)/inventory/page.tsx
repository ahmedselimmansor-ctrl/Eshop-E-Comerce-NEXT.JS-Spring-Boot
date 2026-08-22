'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { PackagePlus, Scale } from 'lucide-react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';
import { ApiError, type StockRow } from '@eshop/api-client';
import { adminApi } from '@/lib/api';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  Field,
  Input,
  Skeleton,
  Table,
  Td,
  Th,
} from '@/components/ui/primitives';
import { Pagination } from '@/components/ui/pagination';
import { hasPermission } from '@/stores/auth-store';
import { formatNumber } from '@/lib/utils';

export default function InventoryPage() {
  return (
    <Suspense fallback={<Skeleton className="h-96 w-full" />}>
      <InventoryList />
    </Suspense>
  );
}

/**
 * Stock levels.
 *
 * Three numbers per row, and the distinction between them matters: on-hand is what is physically
 * there, reserved is what unpaid orders are holding, and available is the difference — the only one
 * that decides whether a customer can buy. Showing on-hand alone is how a store oversells.
 */
function InventoryList() {
  const router = useRouter();
  const params = useSearchParams();
  const queryClient = useQueryClient();

  const q = params.get('q') ?? '';
  const lowStockOnly = params.get('lowStockOnly') === 'true';
  const page = Number(params.get('page') ?? 0);

  const [search, setSearch] = useState(q);
  const [editing, setEditing] = useState<StockRow | null>(null);

  const canWrite = hasPermission('inventory:write');

  const stock = useQuery({
    queryKey: ['admin', 'inventory', { q, lowStockOnly, page }],
    queryFn: () =>
      adminApi().inventory.list({ q: q || undefined, lowStockOnly, page, size: 20 }),
    placeholderData: keepPreviousData,
  });

  function setParam(changes: Record<string, string | undefined>) {
    const next = new URLSearchParams(params.toString());
    for (const [key, value] of Object.entries(changes)) {
      if (!value) next.delete(key);
      else next.set(key, value);
    }
    if (!('page' in changes)) next.delete('page');
    router.replace(`/inventory?${next.toString()}`);
  }

  function refresh() {
    void queryClient.invalidateQueries({ queryKey: ['admin', 'inventory'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  }

  const rows = stock.data?.content ?? [];

  return (
    <div className="mx-auto max-w-7xl space-y-4">
      <h1 className="text-xl font-semibold">Inventory</h1>

      <Card>
        <form
          className="flex flex-wrap items-center gap-2 border-b border-[var(--border)] p-3"
          onSubmit={(event) => {
            event.preventDefault();
            setParam({ q: search.trim() });
          }}
        >
          <div className="min-w-48 flex-1">
            <Input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Product name or SKU…"
              aria-label="Search inventory"
            />
          </div>

          <label className="flex items-center gap-2 text-xs">
            <input
              type="checkbox"
              checked={lowStockOnly}
              onChange={(event) =>
                setParam({ lowStockOnly: event.target.checked ? 'true' : undefined })
              }
            />
            Low stock only
          </label>

          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>

        {stock.isLoading ? (
          <div className="space-y-2 p-3">
            {Array.from({ length: 8 }, (_, index) => (
              <Skeleton key={index} className="h-10 w-full" />
            ))}
          </div>
        ) : stock.isError ? (
          <ErrorState onRetry={() => void stock.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyState
            title={lowStockOnly ? 'Nothing below its reorder level' : 'No stock records'}
            hint={lowStockOnly ? 'Stock levels are healthy.' : undefined}
          />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>Product</Th>
                  <Th>Warehouse</Th>
                  <Th className="text-end">On hand</Th>
                  <Th className="text-end">Reserved</Th>
                  <Th className="text-end">Available</Th>
                  <Th className="text-end">Reorder at</Th>
                  {canWrite ? <Th /> : null}
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="hover:bg-[var(--muted)]">
                    <Td>
                      <p className="line-clamp-1 font-medium">{row.productName}</p>
                      <p className="numeric text-xs text-[var(--muted-foreground)]">{row.sku}</p>
                    </Td>
                    <Td className="text-[var(--muted-foreground)]">{row.warehouse ?? '—'}</Td>
                    <Td className="numeric text-end">{formatNumber(row.onHand)}</Td>
                    <Td className="numeric text-end text-[var(--muted-foreground)]">
                      {formatNumber(row.reserved)}
                    </Td>
                    <Td className="numeric text-end font-medium">
                      {row.available <= 0 ? (
                        <Badge tone="danger">0</Badge>
                      ) : row.lowStock ? (
                        <Badge tone="warning">{formatNumber(row.available)}</Badge>
                      ) : (
                        formatNumber(row.available)
                      )}
                    </Td>
                    <Td className="numeric text-end text-[var(--muted-foreground)]">
                      {formatNumber(row.reorderLevel)}
                    </Td>
                    {canWrite ? (
                      <Td className="text-end">
                        <Button size="sm" variant="outline" onClick={() => setEditing(row)}>
                          Adjust
                        </Button>
                      </Td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </Table>

            {stock.data ? (
              <Pagination
                page={stock.data.page}
                label="SKUs"
                onPageChange={(next) => setParam({ page: String(next) })}
              />
            ) : null}
          </>
        )}
      </Card>

      {editing ? (
        <StockDialog
          row={editing}
          onClose={() => setEditing(null)}
          onDone={() => {
            refresh();
            setEditing(null);
          }}
        />
      ) : null}
    </div>
  );
}

/**
 * Adjust or receive stock.
 *
 * Two genuinely different operations, so they are two forms rather than one with a sign. An
 * adjustment is a stock take — an absolute count, and a reason is mandatory because someone will ask
 * why the number changed. Receiving is a delta from a supplier delivery.
 */
function StockDialog({
  row,
  onClose,
  onDone,
}: {
  row: StockRow;
  onClose: () => void;
  onDone: () => void;
}) {
  const [mode, setMode] = useState<'adjust' | 'receive'>('adjust');
  const [onHand, setOnHand] = useState(String(row.onHand));
  const [reason, setReason] = useState('');
  const [received, setReceived] = useState('1');
  const [note, setNote] = useState('');

  const adjust = useMutation({
    mutationFn: () => adminApi().inventory.adjust(row.id, Number(onHand), reason.trim()),
    onSuccess: onDone,
  });

  const receive = useMutation({
    mutationFn: () =>
      adminApi().inventory.receive(row.id, Number(received), note.trim() || undefined),
    onSuccess: onDone,
  });

  const error = adjust.error ?? receive.error;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <Card className="w-full max-w-md">
        <div className="border-b border-[var(--border)] px-4 py-3">
          <p className="font-semibold">{row.productName}</p>
          <p className="numeric text-xs text-[var(--muted-foreground)]">{row.sku}</p>
        </div>

        <div className="flex gap-1 border-b border-[var(--border)] p-2">
          <Button
            size="sm"
            variant={mode === 'adjust' ? 'primary' : 'ghost'}
            onClick={() => setMode('adjust')}
          >
            <Scale aria-hidden />
            Stock take
          </Button>
          <Button
            size="sm"
            variant={mode === 'receive' ? 'primary' : 'ghost'}
            onClick={() => setMode('receive')}
          >
            <PackagePlus aria-hidden />
            Receive
          </Button>
        </div>

        <div className="space-y-3 p-4">
          {mode === 'adjust' ? (
            <>
              <Field
                id="on-hand"
                label="Counted on hand"
                hint={`Currently ${row.onHand}, of which ${row.reserved} is reserved.`}
                required
              >
                <Input
                  id="on-hand"
                  type="number"
                  min={0}
                  value={onHand}
                  onChange={(event) => setOnHand(event.target.value)}
                />
              </Field>

              <Field id="reason" label="Reason" hint="Recorded on the stock movement." required>
                <Input
                  id="reason"
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  placeholder="Stock take, damage, shrinkage…"
                />
              </Field>

              {Number(onHand) < row.reserved ? (
                <p className="text-xs text-warning-500">
                  This is below the {row.reserved} units already reserved by unpaid orders — the API
                  will refuse it.
                </p>
              ) : null}
            </>
          ) : (
            <>
              <Field id="received" label="Units received" required>
                <Input
                  id="received"
                  type="number"
                  min={1}
                  value={received}
                  onChange={(event) => setReceived(event.target.value)}
                />
              </Field>
              <Field id="note" label="Note">
                <Input
                  id="note"
                  value={note}
                  onChange={(event) => setNote(event.target.value)}
                  placeholder="Supplier, purchase order…"
                />
              </Field>
            </>
          )}

          {error ? (
            <p role="alert" className="text-xs text-danger-500">
              {error instanceof ApiError ? error.detail : 'Something went wrong.'}
            </p>
          ) : null}

          <div className="flex justify-end gap-2">
            <Button variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button
              loading={adjust.isPending || receive.isPending}
              disabled={mode === 'adjust' ? !reason.trim() : !(Number(received) > 0)}
              onClick={() => (mode === 'adjust' ? adjust.mutate() : receive.mutate())}
            >
              Save
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
