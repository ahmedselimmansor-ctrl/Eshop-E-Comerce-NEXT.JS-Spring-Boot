'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { RefreshCw } from 'lucide-react';
import { useState } from 'react';
import { ApiError } from '@eshop/api-client';
import { adminApi } from '@/lib/api';
import {
  Badge,
  Button,
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
import { hasPermission } from '@/stores/auth-store';
import { formatNumber } from '@/lib/utils';

const RANGES = [7, 30, 90];

/**
 * Search health.
 *
 * Two questions an operator has about search: is the index up to date, and what are customers
 * failing to find. The second is the more valuable of the two - every zero-result query is either a
 * product the store should stock or a word the index should recognise.
 */
export default function SearchPage() {
  const queryClient = useQueryClient();
  const canReindex = hasPermission('settings:write');

  const [rangeDays, setRangeDays] = useState(30);

  const status = useQuery({
    queryKey: ['admin', 'search', 'status'],
    queryFn: () => adminApi().search.status(),
  });

  const zeroResults = useQuery({
    queryKey: ['admin', 'search', 'zero-results', rangeDays],
    queryFn: () => adminApi().dashboard.zeroResultSearches(rangeDays, 50),
  });

  const reindex = useMutation({
    mutationFn: () => adminApi().search.reindex(),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['admin', 'search', 'status'] }),
  });

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-semibold">Search</h1>

        {canReindex ? (
          <Button variant="outline" loading={reindex.isPending} onClick={() => reindex.mutate()}>
            <RefreshCw aria-hidden />
            Rebuild index
          </Button>
        ) : null}
      </div>

      {reindex.error ? (
        <p role="alert" className="text-xs text-danger-500">
          {reindex.error instanceof ApiError
            ? reindex.error.detail
            : 'Could not start the reindex.'}
        </p>
      ) : null}

      {reindex.isSuccess ? (
        <p className="rounded-md bg-success-500/10 p-3 text-xs">
          Reindex started. It runs in the background - the counters below catch up as it progresses.
        </p>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle>Index status</CardTitle>
        </CardHeader>

        {status.isLoading ? (
          <CardBody>
            <Skeleton className="h-24 w-full" />
          </CardBody>
        ) : status.isError ? (
          <ErrorState
            onRetry={() => void status.refetch()}
            message="Could not reach the search cluster."
          />
        ) : (
          <CardBody>
            <dl className="grid gap-3 sm:grid-cols-2">
              {Object.entries(status.data ?? {}).map(([key, value]) => (
                <div key={key} className="flex items-baseline justify-between gap-3">
                  <dt className="text-xs text-[var(--muted-foreground)]">{key}</dt>
                  <dd className="numeric text-sm font-medium">
                    {typeof value === 'boolean' ? (
                      <Badge tone={value ? 'success' : 'danger'}>{value ? 'Yes' : 'No'}</Badge>
                    ) : typeof value === 'number' ? (
                      formatNumber(value)
                    ) : (
                      String(value ?? '-')
                    )}
                  </dd>
                </div>
              ))}
            </dl>
          </CardBody>
        )}
      </Card>

      <Card>
        <CardHeader className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle>Searches with no results</CardTitle>
          <Select
            className="w-36"
            value={rangeDays}
            onChange={(event) => setRangeDays(Number(event.target.value))}
            aria-label="Period"
          >
            {RANGES.map((days) => (
              <option key={days} value={days}>
                Last {days} days
              </option>
            ))}
          </Select>
        </CardHeader>

        {zeroResults.isLoading ? (
          <CardBody>
            <Skeleton className="h-40 w-full" />
          </CardBody>
        ) : (zeroResults.data?.length ?? 0) === 0 ? (
          <EmptyState
            title="Every search found something"
            hint="No gaps to act on in this period."
          />
        ) : (
          <Table>
            <thead>
              <tr>
                <Th>Query</Th>
                <Th className="text-end">Searches</Th>
              </tr>
            </thead>
            <tbody>
              {zeroResults.data?.map((row) => (
                <tr key={row.query} className="hover:bg-[var(--muted)]">
                  <Td>{row.query}</Td>
                  <Td className="numeric text-end">{formatNumber(row.searches)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        )}
      </Card>
    </div>
  );
}
