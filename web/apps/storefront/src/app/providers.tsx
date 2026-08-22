'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider } from 'next-themes';
import { useEffect, useState, type ReactNode } from 'react';
import { ApiError } from '@eshop/api-client';
import { browserApi } from '@/lib/api-browser';
import { useAuthStore } from '@/stores/auth-store';
import { clearSessionId, getOrCreateSessionId } from '@/lib/utils';

/**
 * Client-side providers: server-state cache, theme, and session restoration.
 */
export function Providers({ children }: { children: ReactNode }) {
  // Created inside state, not at module scope. A module-level QueryClient is shared across every
  // request in the Node process during SSR, which leaks one visitor's cached data into another's
  // render.
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // Product data changes on a merchandising timescale, not a per-second one. A minute of
            // staleness removes most refetches while keeping the catalog current enough.
            staleTime: 60_000,
            gcTime: 5 * 60_000,
            refetchOnWindowFocus: false,

            retry: (failureCount, error) => {
              // Retrying a 404 or a validation failure just repeats the same answer more slowly.
              if (error instanceof ApiError && !error.retryable) {
                return false;
              }
              return failureCount < 2;
            },
            retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
          },
          mutations: {
            // Never retried automatically: a mutation may have succeeded before the response was
            // lost, and replaying it would place a second order. Retries are the caller's decision,
            // with the original idempotency key.
            retry: false,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="light" enableSystem disableTransitionOnChange>
        <SessionBootstrap />
        {children}
      </ThemeProvider>
    </QueryClientProvider>
  );
}

/**
 * Restores the session on load and merges a guest cart after sign-in.
 *
 * The access token is deliberately not persisted (see `auth-store.ts`), so every page load starts
 * signed out. This exchanges the HttpOnly refresh cookie for a fresh token before anything that
 * depends on it renders.
 */
function SessionBootstrap() {
  const finishInitialising = useAuthStore((state) => state.finishInitialising);
  const setSession = useAuthStore((state) => state.setSession);

  useEffect(() => {
    let cancelled = false;

    async function restore() {
      // Ensures a guest identifier exists before the first cart call, so an anonymous cart is
      // attributable from the very first add.
      getOrCreateSessionId();

      try {
        const response = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
        });

        if (!response.ok) {
          // No cookie, or it was revoked. A signed-out visitor is a normal state, not an error.
          if (!cancelled) finishInitialising();
          return;
        }

        const payload = await response.json();
        if (cancelled) return;

        setSession(payload.user, payload.tokens.accessToken);

        // Folds anything added while browsing anonymously into the customer's cart. Failure here
        // is not worth surfacing — the customer is signed in either way, and the merge is retried
        // on the next sign-in.
        const sessionId = getOrCreateSessionId();
        if (sessionId) {
          try {
            await browserApi().cart.merge(sessionId);
            clearSessionId();
          } catch {
            /* the guest cart stays where it is */
          }
        }
      } catch {
        if (!cancelled) finishInitialising();
      }
    }

    void restore();
    return () => {
      cancelled = true;
    };
  }, [finishInitialising, setSession]);

  return null;
}
