'use client';

import { create } from 'zustand';
import type { User } from '@eshop/api-client';

interface AuthState {
  user: User | null;
  accessToken: string | null;
  /** True until the first refresh attempt settles, so guards do not flash the signed-out UI. */
  initialising: boolean;

  setSession: (user: User, accessToken: string) => void;
  setUser: (user: User) => void;
  setAccessToken: (accessToken: string | null) => void;
  clear: () => void;
  finishInitialising: () => void;
}

/**
 * Authentication state.
 *
 * **The access token is held in memory only** — deliberately not persisted to `localStorage` or a
 * readable cookie. Anything a script on the page can read, an XSS can exfiltrate. The refresh token
 * lives in an `HttpOnly; SameSite=Strict` cookie the page cannot see, so a successful XSS gets at
 * most a 15-minute access token rather than a 30-day session.
 *
 * The cost is that a page reload starts with no token. `AuthProvider` immediately calls refresh,
 * which the browser answers with the cookie, and the session is restored before anything renders
 * that depends on it.
 */
export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  accessToken: null,
  initialising: true,

  setSession: (user, accessToken) => set({ user, accessToken, initialising: false }),
  setUser: (user) => set({ user }),
  setAccessToken: (accessToken) => set({ accessToken }),
  clear: () => set({ user: null, accessToken: null, initialising: false }),
  finishInitialising: () => set({ initialising: false }),
}));

/**
 * Reads the current token outside React.
 *
 * The API client needs the token on every request but is not a component, so it cannot use a hook.
 * Zustand's `getState` is the supported escape hatch and always returns the live value.
 */
export function currentAccessToken(): string | null {
  return useAuthStore.getState().accessToken;
}

export function isSignedIn(): boolean {
  return useAuthStore.getState().user !== null;
}

/** Whether the signed-in customer holds a permission — used to reveal staff-only shortcuts. */
export function hasPermission(permission: string): boolean {
  return useAuthStore.getState().user?.permissions.includes(permission) ?? false;
}
