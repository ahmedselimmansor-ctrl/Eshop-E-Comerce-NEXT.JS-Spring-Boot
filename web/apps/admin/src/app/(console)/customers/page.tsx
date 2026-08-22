'use client';

import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useState } from 'react';
import { ApiError, type UserStatus } from '@eshop/api-client';
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
import { UserStatusBadge, humanise } from '@/components/ui/status';
import { hasPermission } from '@/stores/auth-store';
import { formatDate } from '@/lib/utils';

const STATUSES: UserStatus[] = ['ACTIVE', 'SUSPENDED', 'DELETED'];

export default function CustomersPage() {
  return (
    <Suspense fallback={<Skeleton className="h-96 w-full" />}>
      <CustomersList />
    </Suspense>
  );
}

/**
 * Customer accounts.
 *
 * A support surface, not a CRM: search by whatever the caller gave you (name, email, phone), see the
 * account's state, and suspend it if it is being abused. Anything richer belongs on the order.
 */
function CustomersList() {
  const router = useRouter();
  const params = useSearchParams();
  const queryClient = useQueryClient();

  const q = params.get('q') ?? '';
  const status = (params.get('status') as UserStatus | null) ?? undefined;
  const page = Number(params.get('page') ?? 0);

  const [search, setSearch] = useState(q);
  const canWrite = hasPermission('customer:write');

  const customers = useQuery({
    queryKey: ['admin', 'customers', { q, status, page }],
    queryFn: () => adminApi().customers.list({ q: q || undefined, status, page, size: 20 }),
    placeholderData: keepPreviousData,
  });

  const setStatus = useMutation({
    mutationFn: ({ userId, next }: { userId: string; next: UserStatus }) =>
      adminApi().customers.setStatus(userId, next),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'customers'] }),
  });

  function setParam(changes: Record<string, string | undefined>) {
    const next = new URLSearchParams(params.toString());
    for (const [key, value] of Object.entries(changes)) {
      if (!value) next.delete(key);
      else next.set(key, value);
    }
    if (!('page' in changes)) next.delete('page');
    router.replace(`/customers?${next.toString()}`);
  }

  const rows = customers.data?.content ?? [];

  return (
    <div className="mx-auto max-w-7xl space-y-4">
      <h1 className="text-xl font-semibold">Customers</h1>

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
              placeholder="Name, email, or phone…"
              aria-label="Search customers"
            />
          </div>

          <Select
            className="w-40"
            value={status ?? ''}
            onChange={(event) => setParam({ status: event.target.value })}
            aria-label="Account status"
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

        {customers.isLoading ? (
          <div className="space-y-2 p-3">
            {Array.from({ length: 8 }, (_, index) => (
              <Skeleton key={index} className="h-10 w-full" />
            ))}
          </div>
        ) : customers.isError ? (
          <ErrorState onRetry={() => void customers.refetch()} />
        ) : rows.length === 0 ? (
          <EmptyState title="No customers match these filters" />
        ) : (
          <>
            <Table>
              <thead>
                <tr>
                  <Th>Customer</Th>
                  <Th>Contact</Th>
                  <Th>Status</Th>
                  <Th>Roles</Th>
                  <Th>Joined</Th>
                  {canWrite ? <Th /> : null}
                </tr>
              </thead>
              <tbody>
                {rows.map((customer) => (
                  <tr key={customer.id} className="hover:bg-[var(--muted)]">
                    <Td>
                      <p className="font-medium">{customer.fullName || '-'}</p>
                      <p className="text-xs text-[var(--muted-foreground)]">{customer.email}</p>
                    </Td>

                    <Td className="numeric text-xs text-[var(--muted-foreground)]">
                      {customer.phone ?? '-'}
                      <div className="mt-0.5 flex gap-1">
                        {customer.emailVerified ? (
                          <Badge tone="success">Email ✓</Badge>
                        ) : (
                          <Badge tone="neutral">Email unverified</Badge>
                        )}
                      </div>
                    </Td>

                    <Td>
                      <UserStatusBadge status={customer.status} />
                    </Td>

                    <Td className="text-xs text-[var(--muted-foreground)]">
                      {customer.roles.filter((role) => role !== 'CUSTOMER').join(', ') || 'Customer'}
                    </Td>

                    <Td className="whitespace-nowrap text-xs text-[var(--muted-foreground)]">
                      {formatDate(customer.createdAt)}
                    </Td>

                    {canWrite ? (
                      <Td className="text-end">
                        {customer.status === 'ACTIVE' ? (
                          <Button
                            size="sm"
                            variant="ghost"
                            className="text-danger-500"
                            loading={
                              setStatus.isPending && setStatus.variables?.userId === customer.id
                            }
                            onClick={() =>
                              setStatus.mutate({ userId: customer.id, next: 'SUSPENDED' })
                            }
                          >
                            Suspend
                          </Button>
                        ) : customer.status === 'SUSPENDED' ? (
                          <Button
                            size="sm"
                            variant="outline"
                            loading={
                              setStatus.isPending && setStatus.variables?.userId === customer.id
                            }
                            onClick={() => setStatus.mutate({ userId: customer.id, next: 'ACTIVE' })}
                          >
                            Reinstate
                          </Button>
                        ) : null}
                      </Td>
                    ) : null}
                  </tr>
                ))}
              </tbody>
            </Table>

            {customers.data ? (
              <Pagination
                page={customers.data.page}
                label="customers"
                onPageChange={(next) => setParam({ page: String(next) })}
              />
            ) : null}
          </>
        )}

        {setStatus.error ? (
          <p role="alert" className="px-3 pb-3 text-xs text-danger-500">
            {setStatus.error instanceof ApiError
              ? setStatus.error.detail
              : 'Could not change the account status.'}
          </p>
        ) : null}
      </Card>
    </div>
  );
}
