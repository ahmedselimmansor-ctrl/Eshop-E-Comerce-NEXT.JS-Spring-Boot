'use client';

import Image from 'next/image';
import { useState } from 'react';
import type { ProductDetail } from '@eshop/api-client';
import { cn } from '@/lib/utils';

/**
 * Product image gallery.
 *
 * A client component because selecting a thumbnail is interactive, but the *first* image renders
 * with `priority` so it is still the LCP element and is fetched immediately rather than after
 * hydration.
 */
export function ProductGallery({
  images,
  productName,
}: {
  images: ProductDetail['images'];
  productName: string;
}) {
  const [activeIndex, setActiveIndex] = useState(0);
  const active = images[activeIndex];

  if (images.length === 0) {
    return (
      <div className="aspect-square rounded-[var(--radius-card)] bg-[var(--muted)]" aria-hidden />
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="relative aspect-square overflow-hidden rounded-[var(--radius-card)] border border-[var(--border)] bg-[var(--muted)]">
        {active ? (
          <Image
            src={active.url}
            alt={active.alt || productName}
            fill
            sizes="(max-width: 1024px) 100vw, 50vw"
            className="object-contain"
            // The hero image of the page — always eager, never lazy.
            priority
            placeholder={active.blurhash ? 'blur' : 'empty'}
            blurDataURL={active.blurhash ?? undefined}
          />
        ) : null}
      </div>

      {images.length > 1 ? (
        <ul className="flex gap-2 overflow-x-auto pb-1" role="tablist" aria-label={productName}>
          {images.map((image, index) => (
            <li key={image.id}>
              <button
                type="button"
                role="tab"
                aria-selected={index === activeIndex}
                onClick={() => setActiveIndex(index)}
                className={cn(
                  'relative size-16 shrink-0 overflow-hidden rounded-md border-2 transition-colors',
                  index === activeIndex
                    ? 'border-brand-600'
                    : 'border-[var(--border)] hover:border-brand-300',
                )}
              >
                <Image
                  src={image.url}
                  alt=""
                  fill
                  sizes="64px"
                  className="object-cover"
                  // Thumbnails are below the fold on a phone and are not the LCP candidate.
                  loading="lazy"
                />
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}
