'use client';

import { ArrowLeft } from 'lucide-react';
import Link from 'next/link';
import { Button } from '@/components/ui/primitives';
import { ProductForm } from '@/components/products/product-form';

export default function NewProductPage() {
  return (
    <div className="mx-auto max-w-4xl space-y-4">
      <div className="flex items-center gap-3">
        <Button variant="ghost" size="sm" asChild>
          <Link href="/products">
            <ArrowLeft aria-hidden />
            Products
          </Link>
        </Button>
        <h1 className="text-xl font-semibold">New product</h1>
      </div>

      <ProductForm />
    </div>
  );
}
