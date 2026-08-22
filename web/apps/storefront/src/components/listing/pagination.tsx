'use client';

import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { PageMeta } from '@eshop/api-client';
import { Button } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { useListingParams } from './use-listing-params';

/**
 * Page navigation.
 *
 * Numbered pages rather than infinite scroll: a shopper who opens a product and comes back expects
 * to be where they were, and infinite scroll loses that. Numbered pages are also what makes the
 * listing crawlable - each page has a real URL.
 */
export function Pagination({ page }: { page: PageMeta }) {
  const { update, isPending } = useListingParams();

  if (page.totalPages <= 1) return null;

  const current = page.number;
  const windowed = buildPageWindow(current, page.totalPages);

  function goTo(target: number) {
    update({ page: target === 0 ? null : String(target) });
    // Returns to the top of the results; staying at the bottom of a fresh page is disorienting.
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  return (
    <nav className="mt-8 flex items-center justify-center gap-1" aria-label="Pagination">
      <Button
        variant="outline"
        size="icon"
        disabled={page.first || isPending}
        onClick={() => goTo(current - 1)}
        aria-label="Previous page"
      >
        {/* Flipped in RTL, so "previous" always points backward in reading order. */}
        <ChevronLeft className="rtl-flip" aria-hidden />
      </Button>

      {windowed.map((entry, index) =>
        entry === null ? (
          <span key={`gap-${index}`} className="px-2 text-[var(--muted-foreground)]" aria-hidden>
            …
          </span>
        ) : (
          <Button
            key={entry}
            variant={entry === current ? 'primary' : 'outline'}
            size="icon"
            disabled={isPending}
            onClick={() => goTo(entry)}
            aria-current={entry === current ? 'page' : undefined}
            className={cn('numeric', entry === current && 'pointer-events-none')}
          >
            {entry + 1}
          </Button>
        ),
      )}

      <Button
        variant="outline"
        size="icon"
        disabled={page.last || isPending}
        onClick={() => goTo(current + 1)}
        aria-label="Next page"
      >
        <ChevronRight className="rtl-flip" aria-hidden />
      </Button>
    </nav>
  );
}

/**
 * Builds a bounded page window: first, last, the current page and its neighbours, with `null` as an
 * ellipsis. A listing with 200 pages must not render 200 buttons.
 */
function buildPageWindow(current: number, total: number): (number | null)[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, index) => index);
  }

  const pages = new Set<number>([0, total - 1, current]);

  for (const offset of [-1, 1]) {
    const neighbour = current + offset;
    if (neighbour > 0 && neighbour < total - 1) pages.add(neighbour);
  }

  const sorted = [...pages].sort((a, b) => a - b);
  const result: (number | null)[] = [];

  sorted.forEach((page, index) => {
    if (index > 0 && page - (sorted[index - 1] as number) > 1) {
      result.push(null);
    }
    result.push(page);
  });

  return result;
}
