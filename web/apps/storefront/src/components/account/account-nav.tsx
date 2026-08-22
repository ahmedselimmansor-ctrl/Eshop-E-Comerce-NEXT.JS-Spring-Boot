'use client';

import { Heart, MapPin, Package, User } from 'lucide-react';
import { Link, usePathname } from '@/i18n/routing';
import { cn } from '@/lib/utils';

const ICONS = {
  user: User,
  package: Package,
  'map-pin': MapPin,
  heart: Heart,
} as const;

type AccountRoute = '/account' | '/account/orders' | '/account/addresses' | '/account/wishlist';

interface NavItem {
  href: AccountRoute;
  label: string;
  icon: keyof typeof ICONS;
}

/**
 * Account section navigation.
 *
 * A client component only because it needs the current path to mark the active item. `usePathname`
 * from the locale-aware router returns the *unlocalised* route, so the comparison works in both
 * languages without stripping a prefix.
 */
export function AccountNav({ items }: { items: NavItem[] }) {
  const pathname = usePathname();

  return (
    <nav aria-label="Account sections">
      <ul className="flex gap-1 overflow-x-auto lg:flex-col lg:overflow-visible">
        {items.map((item) => {
          const Icon = ICONS[item.icon];
          // Exact match for the profile root; prefix match elsewhere, so an order detail page keeps
          // "Orders" highlighted.
          const active =
            item.href === '/account' ? pathname === item.href : pathname.startsWith(item.href);

          return (
            <li key={item.href}>
              <Link
                href={item.href}
                aria-current={active ? 'page' : undefined}
                className={cn(
                  'flex items-center gap-2.5 whitespace-nowrap rounded-[var(--radius-control)] px-3 py-2.5 text-sm transition-colors',
                  active
                    ? 'bg-brand-50 font-medium text-brand-700'
                    : 'hover:bg-[var(--muted)]',
                )}
              >
                <Icon className="size-4 shrink-0" aria-hidden />
                {item.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
