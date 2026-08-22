'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { ApiError } from '@eshop/api-client';

export function Providers({ children }: { children: ReactNode }) {
  // Created in state, not at module scope: a module-level client is shared across every SSR request
  // in the process, which leaks one operator's data into another's render.
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // Shorter than the storefront's. An operator acting on stale order data makes decisions
            // that are wrong by the time they take effect.
            staleTime: 15_000,
            refetchOnWindowFocus: true,
            retry: (failureCount, error) =>
              error instanceof ApiError && !error.retryable ? false : failureCount < 2,
          },
          // Never retried automatically - a refund or a status change may have succeeded before the
          // response was lost, and replaying it would apply it twice.
          mutations: { retry: false },
        },
      }),
  );

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
