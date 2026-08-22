import { ProductCardSkeleton } from '@/components/product/product-card';

/**
 * Streamed while the listing loads.
 *
 * The skeleton grid matches the real one's columns and card dimensions exactly, so nothing shifts
 * when the products arrive — a placeholder of the wrong size is worse than none, because it moves
 * the page under the reader.
 */
export default function ProductsLoading() {
  return (
    <div className="mx-auto max-w-7xl px-4 py-8">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
        {Array.from({ length: 12 }, (_, index) => (
          <ProductCardSkeleton key={index} />
        ))}
      </div>
    </div>
  );
}
