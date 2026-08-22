'use client';

import {
  authResponseSchema,
  createAdminApi,
  createApi,
  type EshopAdminApi,
  type EshopApi,
} from '@eshop/api-client';
import { useAuthStore } from '@/stores/auth-store';

/**
 * The console's API clients.
 *
 * Both go through the same-origin proxy at `/api/v1` rather than straight to the backend, so the
 * `SameSite=Strict` refresh cookie is actually sent - see the proxy route for the full reasoning.
 *
 * Two facades share one configuration: `adminApi()` for the privileged endpoints and `api()` for
 * the handful of ordinary ones the console also uses (sign-in, the operator's own profile).
 */
let admin: EshopAdminApi | null = null;
let storefront: EshopApi | null = null;

function config() {
  return {
    baseUrl: '',
    getAccessToken: () => useAuthStore.getState().accessToken,
    // Staff work in English; the API still honours the header for product names it returns.
    getLocale: () => 'en',
    credentials: 'include' as const,

    /**
     * Called once when a call comes back 401.
     *
     * A fifteen-minute token expiring mid-shift is the ordinary case, not an error. This exchanges
     * the cookie for a fresh token and the client replays the request. Returning false means the
     * session is genuinely over, and clearing the store is what sends the operator to the login
     * screen rather than leaving them staring at a failed request.
     */
    refreshAccessToken: async () => {
      try {
        const response = await fetch('/api/v1/auth/refresh', {
          method: 'POST',
          credentials: 'include',
          headers: { 'Content-Type': 'application/json' },
        });

        if (!response.ok) {
          useAuthStore.getState().clear();
          return false;
        }

        const payload = authResponseSchema.parse(await response.json());
        useAuthStore.getState().setSession(payload.user, payload.tokens.accessToken);
        return true;
      } catch {
        useAuthStore.getState().clear();
        return false;
      }
    },
  };
}

export function adminApi(): EshopAdminApi {
  admin ??= createAdminApi(config());
  return admin;
}

export function api(): EshopApi {
  storefront ??= createApi(config());
  return storefront;
}

/** Drops both cached clients - used after sign-out. */
export function resetApi(): void {
  admin = null;
  storefront = null;
}
