'use client';

import { authResponseSchema, createApi, type EshopApi } from '@eshop/api-client';
import { useAuthStore } from '@/stores/auth-store';
import { getOrCreateSessionId } from '@/lib/utils';

/**
 * The API client for the browser.
 *
 * Calls go through the same-origin proxy at `/api/v1` rather than straight to the backend.
 * That is what lets the refresh token stay in a `SameSite=Strict` cookie: a cross-origin request to
 * the API domain would not carry it, and loosening the cookie to `SameSite=None` to make that work
 * would give up the CSRF protection the strict setting provides.
 *
 * A single module-level instance is safe here — unlike on the server, a browser tab has exactly one
 * visitor.
 */
let client: EshopApi | null = null;

export function browserApi(): EshopApi {
  if (client) return client;

  client = createApi({
    baseUrl: '',

    getAccessToken: () => useAuthStore.getState().accessToken,

    getLocale: () => document.documentElement.lang || 'ar',

    getSessionId: () => getOrCreateSessionId(),

    // Sends the HttpOnly refresh cookie on same-origin BFF calls.
    credentials: 'include',

    /**
     * Called once when a request comes back 401.
     *
     * An expired access token is the ordinary case, not an error: the token lasts fifteen minutes
     * and a browsing session lasts longer. This exchanges the cookie for a fresh token and the
     * client replays the original request, so the customer never sees it happen.
     *
     * Returning false means the refresh token is gone or was revoked — the session is genuinely
     * over, and the store is cleared so the UI reflects that.
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

        // Parsed rather than cast: this is a network boundary, and a silently-changed shape here
        // would put a broken user object into global state.
        const payload = authResponseSchema.parse(await response.json());

        useAuthStore.getState().setSession(payload.user, payload.tokens.accessToken);
        return true;
      } catch {
        useAuthStore.getState().clear();
        return false;
      }
    },
  });

  return client;
}

/** Resets the cached client — used by tests and after a sign-out. */
export function resetBrowserApi(): void {
  client = null;
}
