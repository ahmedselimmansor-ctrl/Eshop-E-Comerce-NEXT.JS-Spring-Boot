'use client';

import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import { LogOut, Package, User as UserIcon } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { useQueryClient } from '@tanstack/react-query';
import { Link, useRouter } from '@/i18n/routing';
import { useAuthStore } from '@/stores/auth-store';
import { browserApi } from '@/lib/api-browser';
import { clearSessionId } from '@/lib/utils';

/**
 * Account entry point: a sign-in link when signed out, a menu when signed in.
 *
 * While the session is still being restored it renders the signed-out state rather than a spinner -
 * the restore takes a few hundred milliseconds, and a placeholder that swaps to a name is a more
 * jarring flash than a link that swaps to a name.
 */
export function AccountMenu() {
  const t = useTranslations('common');
  const router = useRouter();
  const queryClient = useQueryClient();

  const user = useAuthStore((state) => state.user);
  const clear = useAuthStore((state) => state.clear);

  async function signOut() {
    try {
      await browserApi().auth.logout();
    } catch {
      // The local session is cleared regardless: a network failure must not leave someone
      // apparently signed in on a shared device.
    }

    clear();
    clearSessionId();
    // Drops every cached response - cart, orders, addresses - so the next visitor on this device
    // cannot read the previous one's data from the cache.
    queryClient.clear();
    router.push('/');
    router.refresh();
  }

  if (!user) {
    return (
      <Link
        href="/auth/login"
        className="inline-flex h-10 items-center gap-1.5 rounded-[var(--radius-control)] px-3 text-sm hover:bg-[var(--muted)]"
      >
        <UserIcon className="size-5" aria-hidden />
        <span className="hidden sm:inline">{t('login')}</span>
      </Link>
    );
  }

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <button
          type="button"
          className="inline-flex h-10 items-center gap-1.5 rounded-[var(--radius-control)] px-3 text-sm hover:bg-[var(--muted)]"
        >
          <UserIcon className="size-5" aria-hidden />
          <span className="hidden max-w-24 truncate sm:inline">{user.firstName}</span>
        </button>
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          // `align="end"` resolves against the document direction, so the menu opens toward the
          // page edge in both LTR and RTL.
          align="end"
          sideOffset={6}
          className="z-50 min-w-52 overflow-hidden rounded-[var(--radius-card)] border border-[var(--border)] bg-[var(--card)] p-1 shadow-lg"
        >
          <div className="px-3 py-2">
            <p className="truncate text-sm font-medium">{user.fullName}</p>
            <p className="truncate text-xs text-[var(--muted-foreground)]">{user.email}</p>
          </div>

          <DropdownMenu.Separator className="my-1 h-px bg-[var(--border)]" />

          <DropdownMenu.Item asChild>
            <Link
              href="/account"
              className="flex cursor-pointer items-center gap-2 rounded px-3 py-2 text-sm outline-none focus:bg-[var(--muted)]"
            >
              <UserIcon className="size-4" aria-hidden />
              {t('account')}
            </Link>
          </DropdownMenu.Item>

          <DropdownMenu.Item asChild>
            <Link
              href="/account/orders"
              className="flex cursor-pointer items-center gap-2 rounded px-3 py-2 text-sm outline-none focus:bg-[var(--muted)]"
            >
              <Package className="size-4" aria-hidden />
              {t('orders')}
            </Link>
          </DropdownMenu.Item>

          <DropdownMenu.Separator className="my-1 h-px bg-[var(--border)]" />

          <DropdownMenu.Item
            onSelect={() => void signOut()}
            className="flex cursor-pointer items-center gap-2 rounded px-3 py-2 text-sm text-danger-500 outline-none focus:bg-[var(--muted)]"
          >
            <LogOut className="size-4" aria-hidden />
            {t('logout')}
          </DropdownMenu.Item>
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
