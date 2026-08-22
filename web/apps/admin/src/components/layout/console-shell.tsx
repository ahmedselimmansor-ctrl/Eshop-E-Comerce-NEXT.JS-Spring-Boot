'use client';

import {
  BarChart3,
  Boxes,
  LogOut,
  Menu,
  MessageSquare,
  Package,
  Search,
  Settings,
  ShoppingCart,
  Users,
  X,
} from 'lucide-react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useState } from 'react';
import { Button } from '@/components/ui/primitives';
import { api, resetApi } from '@/lib/api';
import { useAuthStore } from '@/stores/auth-store';
import { cn } from '@/lib/utils';

/**
 * Navigation, filtered by what the operator can actually do.
 *
 * `permission` mirrors the `@PreAuthorize` on the endpoints behind each section. Hiding a link the
 * operator cannot use is a courtesy, not a control — a support agent who types /products still gets
 * a 403 from the API.
 */
const SECTIONS = [
  { href: '/dashboard', label: 'Dashboard', icon: BarChart3, permission: null },
  { href: '/orders', label: 'Orders', icon: ShoppingCart, permission: 'order:read' },
  { href: '/products', label: 'Products', icon: Package, permission: 'product:read' },
  { href: '/inventory', label: 'Inventory', icon: Boxes, permission: 'inventory:read' },
  { href: '/customers', label: 'Customers', icon: Users, permission: 'customer:read' },
  { href: '/reviews', label: 'Reviews', icon: MessageSquare, permission: 'review:moderate' },
  { href: '/search', label: 'Search', icon: Search, permission: 'settings:read' },
  { href: '/settings', label: 'Settings', icon: Settings, permission: 'settings:read' },
] as const;

export function ConsoleShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const user = useAuthStore((state) => state.user);
  const clear = useAuthStore((state) => state.clear);

  const [menuOpen, setMenuOpen] = useState(false);

  const permissions = user?.permissions ?? [];
  const visible = SECTIONS.filter(
    (section) => section.permission === null || permissions.includes(section.permission),
  );

  async function signOut() {
    // Best-effort: the point is to revoke the refresh cookie server-side, but the local session is
    // cleared either way. An operator who clicks sign out must end up signed out.
    try {
      await api().auth.logout();
    } catch {
      /* ignore */
    }
    clear();
    resetApi();
    router.replace('/login');
  }

  return (
    <div className="flex min-h-dvh">
      {/* ---- sidebar ---- */}
      <aside
        className={cn(
          'fixed inset-y-0 start-0 z-40 flex w-56 flex-col border-e border-[var(--border)]',
          'bg-[var(--card)] transition-transform lg:static lg:translate-x-0',
          menuOpen ? 'translate-x-0' : '-translate-x-full',
        )}
      >
        <div className="flex h-14 items-center justify-between border-b border-[var(--border)] px-4">
          <span className="font-semibold">Eshop Admin</span>
          <Button
            variant="ghost"
            size="icon"
            className="lg:hidden"
            onClick={() => setMenuOpen(false)}
            aria-label="Close navigation"
          >
            <X aria-hidden />
          </Button>
        </div>

        <nav className="flex-1 space-y-0.5 overflow-y-auto p-2" aria-label="Sections">
          {visible.map((section) => {
            const Icon = section.icon;
            const active =
              section.href === '/dashboard'
                ? pathname === section.href
                : pathname.startsWith(section.href);

            return (
              <Link
                key={section.href}
                href={section.href}
                onClick={() => setMenuOpen(false)}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'flex items-center gap-2.5 rounded-md px-2.5 py-2 transition-colors',
                  active ? 'bg-brand-50 font-medium text-brand-700' : 'hover:bg-[var(--muted)]',
                )}
              >
                <Icon className="size-4 shrink-0" aria-hidden />
                {section.label}
              </Link>
            );
          })}
        </nav>

        <div className="border-t border-[var(--border)] p-3">
          <p className="truncate text-xs font-medium">{user?.fullName ?? user?.email}</p>
          <p className="truncate text-xs text-[var(--muted-foreground)]">
            {user?.roles.join(', ')}
          </p>
          <Button variant="ghost" size="sm" className="mt-2 w-full justify-start" onClick={signOut}>
            <LogOut aria-hidden />
            Sign out
          </Button>
        </div>
      </aside>

      {/* Dimmer for the mobile drawer. */}
      {menuOpen ? (
        <button
          type="button"
          className="fixed inset-0 z-30 bg-black/30 lg:hidden"
          onClick={() => setMenuOpen(false)}
          aria-label="Close navigation"
        />
      ) : null}

      {/* ---- content ---- */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex h-14 items-center gap-2 border-b border-[var(--border)] bg-[var(--card)] px-4 lg:hidden">
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setMenuOpen(true)}
            aria-label="Open navigation"
          >
            <Menu aria-hidden />
          </Button>
          <span className="font-semibold">Eshop Admin</span>
        </header>

        <main className="min-w-0 flex-1 p-4 lg:p-6">{children}</main>
      </div>
    </div>
  );
}
