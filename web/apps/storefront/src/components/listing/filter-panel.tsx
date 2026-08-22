'use client';

import { SlidersHorizontal, X } from 'lucide-react';
import { useState } from 'react';
import type { Facets } from '@eshop/api-client';
import { Button } from '@/components/ui/button';
import { Input, Separator } from '@/components/ui/primitives';
import { cn } from '@/lib/utils';
import { useListingParams } from './use-listing-params';

interface FilterLabels {
  filters: string;
  price: string;
  priceFrom: string;
  priceTo: string;
  brands: string;
  categories: string;
  inStockOnly: string;
  onSaleOnly: string;
  rating4Plus: string;
  clearFilters: string;
  apply: string;
  facetsUnavailable: string;
}

/**
 * The filter sidebar.
 *
 * A sidebar on desktop and a full-screen sheet on mobile - the same component, because the filter
 * logic is identical and only the container differs.
 */
export function FilterPanel({
  facets,
  countsReliable,
  labels,
}: {
  facets: Facets;
  countsReliable: boolean;
  labels: FilterLabels;
}) {
  const [sheetOpen, setSheetOpen] = useState(false);
  const { activeFilterCount } = useListingParams();
  const activeCount = activeFilterCount();

  return (
    <>
      {/* Mobile trigger */}
      <div className="lg:hidden">
        <Button variant="outline" fullWidth onClick={() => setSheetOpen(true)}>
          <SlidersHorizontal aria-hidden />
          {labels.filters}
          {activeCount > 0 ? <span className="numeric">({activeCount})</span> : null}
        </Button>
      </div>

      {/* Desktop sidebar */}
      <aside className="hidden lg:block">
        <FilterContents facets={facets} countsReliable={countsReliable} labels={labels} />
      </aside>

      {/* Mobile sheet */}
      {sheetOpen ? (
        <div className="fixed inset-0 z-50 lg:hidden" role="dialog" aria-modal="true">
          <div
            className="absolute inset-0 bg-black/40"
            onClick={() => setSheetOpen(false)}
            aria-hidden
          />
          <div className="absolute inset-y-0 end-0 flex w-full max-w-sm flex-col bg-[var(--background)] shadow-xl">
            <div className="flex items-center justify-between border-b border-[var(--border)] p-4">
              <h2 className="font-semibold">{labels.filters}</h2>
              <button
                type="button"
                onClick={() => setSheetOpen(false)}
                className="rounded p-1 hover:bg-[var(--muted)]"
                aria-label="Close"
              >
                <X className="size-5" aria-hidden />
              </button>
            </div>

            <div className="flex-1 overflow-y-auto p-4">
              <FilterContents facets={facets} countsReliable={countsReliable} labels={labels} />
            </div>

            <div className="border-t border-[var(--border)] p-4">
              <Button fullWidth onClick={() => setSheetOpen(false)}>
                {labels.apply}
              </Button>
            </div>
          </div>
        </div>
      ) : null}
    </>
  );
}

function FilterContents({
  facets,
  countsReliable,
  labels,
}: {
  facets: Facets;
  countsReliable: boolean;
  labels: FilterLabels;
}) {
  const { get, has, update, toggle, clearFilters, activeFilterCount, isPending } =
    useListingParams();

  const [minPrice, setMinPrice] = useState(get('minPrice') ?? '');
  const [maxPrice, setMaxPrice] = useState(get('maxPrice') ?? '');

  const hasActiveFilters = activeFilterCount() > 0;

  return (
    <div className={cn('space-y-6', isPending && 'opacity-60 transition-opacity')}>
      {hasActiveFilters ? (
        <Button variant="ghost" size="sm" onClick={clearFilters} className="text-danger-500">
          <X aria-hidden />
          {labels.clearFilters}
        </Button>
      ) : null}

      {/* Toggles */}
      <fieldset className="space-y-2.5">
        <CheckboxRow
          label={labels.inStockOnly}
          checked={get('inStock') === 'true'}
          onChange={(checked) => update({ inStock: checked ? 'true' : null })}
        />
        <CheckboxRow
          label={labels.onSaleOnly}
          checked={get('onSale') === 'true'}
          onChange={(checked) => update({ onSale: checked ? 'true' : null })}
        />
        <CheckboxRow
          label={labels.rating4Plus}
          checked={get('minRating') === '4'}
          onChange={(checked) => update({ minRating: checked ? '4' : null })}
        />
      </fieldset>

      <Separator />

      {/* Price range */}
      <div>
        <h3 className="mb-2.5 text-sm font-semibold">{labels.price}</h3>
        <div className="flex items-center gap-2">
          <Input
            type="number"
            inputMode="numeric"
            min={0}
            value={minPrice}
            onChange={(event) => setMinPrice(event.target.value)}
            // Committed on blur rather than on every keystroke - an input event per digit would
            // fire a request per digit.
            onBlur={() => update({ minPrice: minPrice || null })}
            placeholder={labels.priceFrom}
            className="numeric h-9"
            aria-label={labels.priceFrom}
          />
          <span className="text-[var(--muted-foreground)]">–</span>
          <Input
            type="number"
            inputMode="numeric"
            min={0}
            value={maxPrice}
            onChange={(event) => setMaxPrice(event.target.value)}
            onBlur={() => update({ maxPrice: maxPrice || null })}
            placeholder={labels.priceTo}
            className="numeric h-9"
            aria-label={labels.priceTo}
          />
        </div>

        {facets.priceRange.min !== null && facets.priceRange.max !== null ? (
          <p className="numeric mt-1.5 text-xs text-[var(--muted-foreground)]">
            {Math.floor(facets.priceRange.min)} – {Math.ceil(facets.priceRange.max)}
          </p>
        ) : null}
      </div>

      {/*
       * Facet counts are only shown when they can be trusted. In the PostgreSQL fallback the counts
       * ignore the other active filters, and a count that does not match what clicking it produces
       * is worse than no count at all.
       */}
      {!countsReliable ? (
        <p className="rounded-md bg-warning-500/15 px-3 py-2 text-xs text-yellow-800">
          {labels.facetsUnavailable}
        </p>
      ) : null}

      {facets.brands.length > 0 ? (
        <>
          <Separator />
          <FacetGroup
            title={labels.brands}
            buckets={facets.brands}
            showCounts={countsReliable}
            isChecked={(key) => has('brand', key)}
            onToggle={(key) => toggle('brand', key)}
          />
        </>
      ) : null}

      {facets.categories.length > 0 ? (
        <>
          <Separator />
          <FacetGroup
            title={labels.categories}
            buckets={facets.categories}
            showCounts={countsReliable}
            isChecked={(key) => get('category') === key}
            onToggle={(key) => update({ category: get('category') === key ? null : key })}
          />
        </>
      ) : null}

      {Object.entries(facets.attributes).map(([code, buckets]) =>
        buckets.length > 0 ? (
          <div key={code}>
            <Separator className="mb-6" />
            <FacetGroup
              title={code}
              buckets={buckets}
              showCounts={countsReliable}
              isChecked={(key) => has(`attr_${code}`, key)}
              onToggle={(key) => toggle(`attr_${code}`, key)}
            />
          </div>
        ) : null,
      )}
    </div>
  );
}

function FacetGroup({
  title,
  buckets,
  showCounts,
  isChecked,
  onToggle,
}: {
  title: string;
  buckets: { key: string; label: string; count: number }[];
  showCounts: boolean;
  isChecked: (key: string) => boolean;
  onToggle: (key: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  // Long facet lists are collapsed: a sidebar with forty brands pushes everything else off-screen.
  const visible = expanded ? buckets : buckets.slice(0, 6);

  return (
    <fieldset>
      <legend className="mb-2.5 text-sm font-semibold capitalize">{title}</legend>

      <div className="space-y-2">
        {visible.map((bucket) => (
          <CheckboxRow
            key={bucket.key}
            label={bucket.label}
            count={showCounts ? bucket.count : undefined}
            checked={isChecked(bucket.key)}
            onChange={() => onToggle(bucket.key)}
          />
        ))}
      </div>

      {buckets.length > 6 ? (
        <button
          type="button"
          onClick={() => setExpanded((value) => !value)}
          className="mt-2 text-xs text-brand-700 hover:underline"
        >
          {expanded ? '−' : `+${buckets.length - 6}`}
        </button>
      ) : null}
    </fieldset>
  );
}

function CheckboxRow({
  label,
  count,
  checked,
  onChange,
}: {
  label: string;
  count?: number;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="flex cursor-pointer items-center gap-2.5 text-sm">
      <input
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        className="size-4 shrink-0 rounded border-[var(--input)] accent-brand-600"
      />
      <span className="flex-1">{label}</span>
      {count !== undefined ? (
        <span className="numeric text-xs text-[var(--muted-foreground)]">{count}</span>
      ) : null}
    </label>
  );
}
