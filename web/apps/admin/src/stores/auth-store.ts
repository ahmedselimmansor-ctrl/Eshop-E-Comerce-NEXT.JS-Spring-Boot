'use client';

import { create } from 'zustand';
import type { User } from '@eshop/api-client';

interface AuthState {
  user: User | null;
  accessToken: string | null;
  /** True until the first refresh attempt settles, so the guard does not flash the login screen. */
  initialising: boolean;

  setSession: (user: User, accessToken: string) => void;
  setUser: (user: User) => void;
  clear: () => void;
  finishInitialising: () => void;
}

/**
 * Operator session.
 *
 * The access token is held in memory only - never `localStorage`, never a readable cookie. An
 * operations console is a higher-value target than the storefront: one stolen token here can refund
 * money and edit prices. The refresh token lives in an `HttpOnly; SameSite=Strict` cookie the page
 * cannot read, so an XSS gets at most a fifteen-minute credential rather than a thirty-day one.
 */
export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  accessToken: null,
  initialising: true,

  setSession: (user, accessToken) => set({ user, accessToken, initialising: false }),
  setUser: (user) => set({ user }),
  clear: () => set({ user: null, accessToken: null, initialising: false }),
  finishInitialising: () => set({ initialising: false }),
}));

/** Reads the token outside React - the API client needs it on every call but is not a component. */
export function currentAccessToken(): string | null {
  return useAuthStore.getState().accessToken;
}

/**
 * Whether the operator holds a permission.
 *
 * Used to hide controls they cannot use. This is presentation only - every one of these actions is
 * independently enforced by `@PreAuthorize` on the endpoint, because a hidden button is not a
 * security boundary.
 */
export function hasPermission(permission: string): boolean {
  const user = useAuthStore.getState().user;
  return user?.permissions.includes(permission) ?? false;
}

/** True when the operator holds any of the listed permissions. */
export function hasAnyPermission(permissions: string[]): boolean {
  return permissions.some(hasPermission);
}
