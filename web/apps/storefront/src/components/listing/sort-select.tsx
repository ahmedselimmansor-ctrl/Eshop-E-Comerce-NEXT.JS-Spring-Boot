'use client';

import { Select } from '@/components/ui/primitives';
import { useListingParams } from './use-listing-params';

/**
 * Sort order.
 *
 * A native `<select>` rather than a custom dropdown: it is keyboard-accessible for free and opens
 * the platform picker on a phone, which is a better experience than any web reimplementation of a
 * wheel picker.
 */
export function SortSelect({
  current,
  labels,
}: {
  current: string;
  labels: {
    sortBy: string;
    relevance: string;
    priceAsc: string;
    priceDesc: string;
    newest: string;
    bestSelling: string;
    rating: string;
  };
}) {
  const { update, isPending } = useListingParams();

  const options = [
    { value: 'relevance', label: labels.relevance },
    { value: 'newest', label: labels.newest },
    { value: 'price_asc', label: labels.priceAsc },
    { value: 'price_desc', label: labels.priceDesc },
    { value: 'best_selling', label: labels.bestSelling },
    { value: 'rating', label: labels.rating },
  ];

  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="text-[var(--muted-foreground)]">{labels.sortBy}</span>
      <Select
        value={current}
        disabled={isPending}
        onChange={(event) => update({ sort: event.target.value })}
        className="h-9 w-auto min-w-40"
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </Select>
    </label>
  );
}
