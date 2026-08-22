import Image from 'next/image';
import { Star } from 'lucide-react';
import { formatMoney, type ProductCard as ProductCardData } from '@eshop/api-client';
import { Link } from '@/i18n/routing';
import { Badge } from '@/components/ui/primitives';
import { cn } from '@/lib/utils';

/**
 * A product tile in a grid.
 *
 * A server component — it renders from data the page already fetched and needs no interactivity, so
 * shipping React for it would cost bundle size for nothing. The add-to-cart control that *does*
 * need interactivity is a separate client island on the detail page.
 */
export function ProductCard({
  product,
  locale,
  priority = false,
  labels,
}: {
  product: ProductCardData;
  locale: string;
  /**
   * Marks the image as high priority. Set for the first row only — marking everything priority
   * tells the browser nothing and delays the one image that is actually the LCP element.
   */
  priority?: boolean;
  labels: {
    outOfStock: string;
    save: (percent: number) => string;
    reviewCount: (count: number) => string;
  };
}) {
  const outOfStock = product.inStock === false;

  return (
    <Link
      href={{ pathname: '/products/[slug]', params: { slug: product.slug } }}
      className={cn(
        'group flex flex-col overflow-hidden rounded-[var(--radius-card)] border border-[var(--border)]',
        'bg-[var(--card)] transition-shadow hover:shadow-[var(--shadow-card-hover)]',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--ring)]',
      )}
    >
      <div className="relative aspect-product overflow-hidden bg-[var(--muted)]">
        {product.imageUrl ? (
          <Image
            src={product.imageUrl}
            alt={product.name}
            fill
            // Tells the browser the rendered width per breakpoint so it picks the right source
            // rather than downloading a desktop-sized image for a phone.
            sizes="(max-width: 640px) 50vw, (max-width: 1024px) 33vw, 25vw"
            className={cn(
              'object-cover transition-transform duration-300 group-hover:scale-105',
              outOfStock && 'opacity-60',
            )}
            priority={priority}
            // Inline placeholder from the blurhash the API already sent — no second request.
            placeholder={product.imageBlurhash ? 'blur' : 'empty'}
            blurDataURL={product.imageBlurhash ?? undefined}
          />
        ) : (
          <div className="flex h-full items-center justify-center text-[var(--muted-foreground)]">
            <span className="text-xs">—</span>
          </div>
        )}

        {/* Logical inset properties, so badges sit top-start in both directions. */}
        {product.discountPercent > 0 && !outOfStock ? (
          <Badge tone="sale" className="absolute top-2 start-2">
            {labels.save(product.discountPercent)}
          </Badge>
        ) : null}

        {outOfStock ? (
          <div className="absolute inset-0 flex items-center justify-center bg-black/40">
            <Badge tone="neutral" className="bg-white/95 text-black">
              {labels.outOfStock}
            </Badge>
          </div>
        ) : null}
      </div>

      <div className="flex flex-1 flex-col gap-1.5 p-3">
        {product.brandName ? (
          <span className="text-xs text-[var(--muted-foreground)]">{product.brandName}</span>
        ) : null}

        {/* Clamped to two lines so every card in a row is the same height. */}
        <h3 className="line-clamp-2 text-sm font-medium leading-snug">{product.name}</h3>

        {product.ratingCount > 0 ? (
          <div className="flex items-center gap-1 text-xs text-[var(--muted-foreground)]">
            <Star className="size-3.5 fill-warning-500 text-warning-500" aria-hidden />
            <span className="numeric">{product.ratingAvg.toFixed(1)}</span>
            <span className="sr-only">{labels.reviewCount(product.ratingCount)}</span>
            <span aria-hidden>({product.ratingCount})</span>
          </div>
        ) : null}

        <div className="mt-auto flex flex-wrap items-baseline gap-2 pt-1">
          <span className="numeric text-base font-semibold">
            {formatMoney(product.price, locale)}
          </span>

          {product.compareAtPrice ? (
            <span className="numeric text-xs text-[var(--muted-foreground)] line-through">
              {formatMoney(product.compareAtPrice, locale)}
            </span>
          ) : null}
        </div>
      </div>
    </Link>
  );
}

/** Matches the card's dimensions exactly, so the grid does not reflow when data arrives. */
export function ProductCardSkeleton() {
  return (
    <div className="overflow-hidden rounded-[var(--radius-card)] border border-[var(--border)]">
      <div className="skeleton aspect-product" />
      <div className="flex flex-col gap-2 p-3">
        <div className="skeleton h-3 w-16 rounded" />
        <div className="skeleton h-4 w-full rounded" />
        <div className="skeleton h-4 w-2/3 rounded" />
        <div className="skeleton mt-2 h-5 w-24 rounded" />
      </div>
    </div>
  );
}
