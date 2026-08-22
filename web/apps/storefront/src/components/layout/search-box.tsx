'use client';

import { useQuery } from '@tanstack/react-query';
import { Search, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import Image from 'next/image';
import { useRouter } from '@/i18n/routing';
import { browserApi } from '@/lib/api-browser';

/**
 * Search box with type-ahead suggestions.
 *
 * Two details make this behave correctly under real typing:
 *
 * 1. The query is **debounced** - one request per pause, not one per keystroke.
 * 2. Each request is **abortable**, and TanStack Query cancels the previous one when the term
 *    changes. Without that, a slow earlier request can resolve last and overwrite fresher
 *    suggestions with stale ones, which looks like the box ignoring what you typed.
 */
export function SearchBox({ placeholder }: { placeholder: string }) {
  const router = useRouter();
  const [term, setTerm] = useState('');
  const [debounced, setDebounced] = useState('');
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term.trim()), 250);
    return () => clearTimeout(timer);
  }, [term]);

  const { data, isFetching } = useQuery({
    queryKey: ['suggest', debounced],
    // The signal comes from TanStack Query and is aborted when the key changes.
    queryFn: ({ signal }) => browserApi().search.suggest(debounced, 6, signal),
    // Two characters is the shortest term that produces useful results; one matches almost
    // everything and wastes a round trip on every session.
    enabled: debounced.length >= 2,
    staleTime: 30_000,
    retry: false,
  });

  // Closes the dropdown on an outside click. Escape is handled on the input itself.
  useEffect(() => {
    function onPointerDown(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, []);

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const query = term.trim();
    if (!query) return;

    setOpen(false);
    router.push({ pathname: '/search', query: { q: query } });
  }

  const suggestions = data?.products ?? [];
  const showDropdown = open && debounced.length >= 2 && suggestions.length > 0;

  return (
    <div ref={containerRef} className="relative w-full max-w-xl">
      <form onSubmit={submit} role="search">
        <div className="relative">
          {/* Logical inset so the icon sits at the leading edge in both directions. */}
          <Search
            className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-[var(--muted-foreground)]"
            aria-hidden
          />

          <input
            type="search"
            value={term}
            onChange={(event) => {
              setTerm(event.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onKeyDown={(event) => {
              if (event.key === 'Escape') setOpen(false);
            }}
            placeholder={placeholder}
            className="h-10 w-full rounded-full border border-[var(--input)] bg-[var(--muted)] ps-9 pe-9 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]"
            aria-label={placeholder}
            aria-expanded={showDropdown}
            aria-autocomplete="list"
            role="combobox"
            aria-controls="search-suggestions"
          />

          {term ? (
            <button
              type="button"
              onClick={() => {
                setTerm('');
                setOpen(false);
              }}
              className="absolute end-3 top-1/2 -translate-y-1/2 text-[var(--muted-foreground)] hover:text-[var(--foreground)]"
              aria-label="Clear"
            >
              <X className="size-4" aria-hidden />
            </button>
          ) : null}
        </div>
      </form>

      {showDropdown ? (
        <ul
          id="search-suggestions"
          role="listbox"
          className="absolute inset-x-0 top-full z-50 mt-2 overflow-hidden rounded-[var(--radius-card)] border border-[var(--border)] bg-[var(--card)] shadow-lg"
        >
          {suggestions.map((product) => (
            <li key={product.id} role="option" aria-selected={false}>
              <button
                type="button"
                onClick={() => {
                  // Relevance feedback, deliberately not awaited - the navigation should not wait
                  // for an analytics write.
                  void browserApi().search.recordClick(debounced, product.id);
                  setOpen(false);
                  router.push({ pathname: '/products/[slug]', params: { slug: product.slug } });
                }}
                className="flex w-full items-center gap-3 px-3 py-2 text-start hover:bg-[var(--muted)]"
              >
                <div className="relative size-10 shrink-0 overflow-hidden rounded bg-[var(--muted)]">
                  {product.imageUrl ? (
                    <Image src={product.imageUrl} alt="" fill sizes="40px" className="object-cover" />
                  ) : null}
                </div>
                <span className="line-clamp-1 flex-1 text-sm">{product.name}</span>
              </button>
            </li>
          ))}

          {isFetching ? (
            <li className="px-3 py-2 text-xs text-[var(--muted-foreground)]" aria-live="polite">
              …
            </li>
          ) : null}
        </ul>
      ) : null}
    </div>
  );
}
