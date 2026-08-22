'use client';

import { useCallback, useTransition } from 'react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';

/**
 * Reads and writes listing filters through the URL.
 *
 * The URL is the single source of truth for filter state. Keeping it there rather than in React
 * state means a filtered view is shareable, survives a reload, and works with the browser's back
 * button - all of which a client-side filter store gives up, and all of which shoppers use.
 *
 * Uses the plain `next/navigation` router rather than the locale-aware one: these are query-string
 * updates on the current path, so there is no route to localise.
 */
export function useListingParams() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [isPending, startTransition] = useTransition();

  const get = useCallback(
    (key: string): string | undefined => searchParams.get(key) ?? undefined,
    [searchParams],
  );

  const getAll = useCallback((key: string): string[] => searchParams.getAll(key), [searchParams]);

  const has = useCallback(
    (key: string, value: string): boolean => searchParams.getAll(key).includes(value),
    [searchParams],
  );

  /**
   * Applies a set of changes.
   *
   * `null` removes a parameter; an array replaces every occurrence. Any change resets `page` to the
   * first - leaving a shopper on page 7 of a result set that now has two pages shows them an empty
   * grid and reads as a broken filter.
   */
  const update = useCallback(
    (changes: Record<string, string | string[] | null | undefined>) => {
      const next = new URLSearchParams(searchParams.toString());

      for (const [key, value] of Object.entries(changes)) {
        next.delete(key);

        if (value === null || value === undefined || value === '') continue;

        if (Array.isArray(value)) {
          value.forEach((entry) => next.append(key, entry));
        } else {
          next.set(key, value);
        }
      }

      if (!('page' in changes)) {
        next.delete('page');
      }

      startTransition(() => {
        // `replace`, not `push`: ticking four filter boxes should not leave four entries in the
        // history for the back button to walk through one at a time.
        router.replace(`${pathname}?${next.toString()}`, { scroll: false });
      });
    },
    [pathname, router, searchParams],
  );

  /** Adds or removes one value of a repeatable parameter. */
  const toggle = useCallback(
    (key: string, value: string) => {
      const current = searchParams.getAll(key);
      const next = current.includes(value)
        ? current.filter((entry) => entry !== value)
        : [...current, value];

      update({ [key]: next.length > 0 ? next : null });
    },
    [searchParams, update],
  );

  /** Clears every filter, keeping the sort order and search term - preferences, not filters. */
  const clearFilters = useCallback(() => {
    const sort = searchParams.get('sort');
    const query = searchParams.get('q');

    const next = new URLSearchParams();
    if (sort) next.set('sort', sort);
    if (query) next.set('q', query);

    const suffix = next.toString();
    startTransition(() => {
      router.replace(`${pathname}${suffix ? `?${suffix}` : ''}`, { scroll: false });
    });
  }, [pathname, router, searchParams]);

  /** How many filters are active, for the mobile "Filters (3)" badge. */
  const activeFilterCount = useCallback((): number => {
    let count = 0;
    searchParams.forEach((_, key) => {
      if (!['sort', 'page', 'size', 'q'].includes(key)) count++;
    });
    return count;
  }, [searchParams]);

  return { get, getAll, has, update, toggle, clearFilters, activeFilterCount, isPending };
}
