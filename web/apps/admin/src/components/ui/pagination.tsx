'use client';

import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { PageMeta } from '@eshop/api-client';
import { Button } from '@/components/ui/primitives';
import { formatNumber } from '@/lib/utils';

/**
 * Table pagination.
 *
 * Deliberately previous/next rather than numbered pages: the console pages over live data, so page
 * seven is a different set of rows by the time anyone clicks it, and the count is what operators
 * actually read off this control.
 */
export function Pagination({
  page,
  onPageChange,
  label = 'rows',
}: {
  page: PageMeta;
  onPageChange: (next: number) => void;
  label?: string;
}) {
  if (page.totalElements === 0) return null;

  const from = page.number * page.size + 1;
  const to = Math.min(from + page.size - 1, page.totalElements);

  return (
    <div className="flex items-center justify-between gap-3 px-3 py-2 text-xs text-[var(--muted-foreground)]">
      <span className="numeric">
        {formatNumber(from)}–{formatNumber(to)} of {formatNumber(page.totalElements)} {label}
      </span>

      <div className="flex items-center gap-1">
        <Button
          variant="outline"
          size="sm"
          disabled={page.first}
          onClick={() => onPageChange(page.number - 1)}
          aria-label="Previous page"
        >
          <ChevronLeft aria-hidden />
          Previous
        </Button>

        <Button
          variant="outline"
          size="sm"
          disabled={page.last}
          onClick={() => onPageChange(page.number + 1)}
          aria-label="Next page"
        >
          Next
          <ChevronRight aria-hidden />
        </Button>
      </div>
    </div>
  );
}
