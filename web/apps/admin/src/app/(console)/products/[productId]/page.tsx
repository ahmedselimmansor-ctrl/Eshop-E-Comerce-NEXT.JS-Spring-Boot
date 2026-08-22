'use client';

import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, ExternalLink } from 'lucide-react';
import Link from 'next/link';
import { use } from 'react';
import { adminApi } from '@/lib/api';
import { Button, ErrorState, Skeleton } from '@/components/ui/primitives';
import { ProductForm } from '@/components/products/product-form';

/**
 * Edit a product.
 *
 * Loads through the admin endpoint rather than the storefront one, which is what makes drafts and
 * archived products reachable — the public detail endpoint would 404 on both.
 */
export default function EditProductPage({ params }: { params: Promise<{ productId: string }> }) {
  const { productId } = use(params);

  const product = useQuery({
    queryKey: ['admin', 'product', productId],
    queryFn: () => adminApi().products.get(productId),
  });

  if (product.isLoading) return <Skeleton className="h-96 w-full" />;
  if (product.isError || !product.data) {
    return <ErrorState onRetry={() => void product.refetch()} />;
  }

  const storefrontUrl = process.env.NEXT_PUBLIC_STOREFRONT_URL;

  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/products">
            <ArrowLeft aria-hidden />
            Products
          </Link>
        </Button>

        <h1 className="line-clamp-1 text-xl font-semibold">
          {product.data.nameEn || product.data.nameAr}
        </h1>

        {storefrontUrl ? (
          <Button variant="ghost" size="sm" className="ms-auto" asChild>
            <a
              href={`${storefrontUrl}/ar/products/${product.data.slug}`}
              target="_blank"
              rel="noopener noreferrer"
            >
              <ExternalLink aria-hidden />
              View on store
            </a>
          </Button>
        ) : null}
      </div>

      <ProductForm product={product.data} />
    </div>
  );
}
