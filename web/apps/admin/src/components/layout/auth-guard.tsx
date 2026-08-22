'use client';

import { useRouter } from 'next/navigation';
import { useEffect, type ReactNode } from 'react';
import { authResponseSchema } from '@eshop/api-client';
import { useAuthStore } from '@/stores/auth-store';
import { Skeleton } from '@/components/ui/primitives';

/**
 * Restores the session, then gates everything behind it.
 *
 * The access token is deliberately not persisted, so every reload starts signed out. This exchanges
 * the `HttpOnly` refresh cookie for a fresh token before anything renders — without it a reload
 * would bounce the operator to the login screen mid-task.
 *
 * This is presentation, not security. Every endpoint behind it authenticates and authorises on its
 * own; removing this component would make the console look broken, not make it permissive.
 */
export function AuthGuard({ children }: { children: ReactNode }) {
  const router = useRouter();

  const user = useAuthStore((state) => state.user);
  const initialising = useAuthStore((state) => state.initialising);
  const setSession = useAuthStore((state) => state.setSession);
  const clear = useAuthStore((state) => state.clear);

  useEffect(() => {
    // Already restored — a client-side navigation, not a reload.
    if (useAuthStore.getState().user) {
      useAuthStore.getState().finishInitialising();
      return;
    }

    let cancelled = false;

    void (async () => {
      try {
        const response = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
        });

        if (!response.ok) {
          if (!cancelled) clear();
          return;
        }

        const payload = authResponseSchema.parse(await response.json());
        if (!cancelled) setSession(payload.user, payload.tokens.accessToken);
      } catch {
        if (!cancelled) clear();
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [clear, setSession]);

  useEffect(() => {
    if (!initialising && !user) {
      router.replace('/login');
    }
  }, [initialising, user, router]);

  if (initialising) {
    return (
      <div className="space-y-4 p-6">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  // The redirect above is already in flight; rendering nothing avoids a flash of the console.
  if (!user) return null;

  return <>{children}</>;
}
