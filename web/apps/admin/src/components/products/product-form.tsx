'use client';

import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Trash2 } from 'lucide-react';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import {
  ApiError,
  productWriteSchema,
  type AdminProductDetail,
  type ProductWriteRequest,
} from '@eshop/api-client';
import { adminApi, api } from '@/lib/api';
import {
  Button,
  Card,
  CardBody,
  CardHeader,
  CardTitle,
  Field,
  Input,
  Select,
  Textarea,
} from '@/components/ui/primitives';
import { ImageUploader } from '@/components/products/image-uploader';
import { hasPermission } from '@/stores/auth-store';

/**
 * Create or edit a product.
 *
 * Validated against the same Zod schema the API client uses for the request body, so the rules the
 * form enforces and the rules the request must satisfy cannot drift apart.
 *
 * Arabic is the required name; English is optional. That is the storefront's fallback order - a
 * product with only an English name would render as a blank tile to an Arabic visitor, which is the
 * default audience.
 */
export function ProductForm({ product }: { product?: AdminProductDetail }) {
  const router = useRouter();
  const editing = Boolean(product);
  const canWrite = hasPermission('product:write');

  const [images, setImages] = useState<string[]>(product?.images.map((image) => image.url) ?? []);

  // Reference data for the two selects. Cached generously - a taxonomy changes rarely.
  const brands = useQuery({
    queryKey: ['reference', 'brands'],
    queryFn: () => api().catalog.brands(),
    staleTime: 5 * 60_000,
  });

  const categories = useQuery({
    queryKey: ['reference', 'categories'],
    queryFn: () => api().catalog.categoryTree(),
    staleTime: 5 * 60_000,
  });

  const form = useForm<ProductWriteRequest>({
    resolver: zodResolver(productWriteSchema),
    // Every field round-trips: the admin detail endpoint returns the stored values rather than a
    // locale-resolved view, so saving an unedited form is a no-op instead of a silent overwrite.
    defaultValues: product
      ? {
          sku: product.sku,
          slug: product.slug,
          nameAr: product.nameAr,
          nameEn: product.nameEn ?? undefined,
          shortDescriptionAr: product.shortDescriptionAr ?? undefined,
          shortDescriptionEn: product.shortDescriptionEn ?? undefined,
          descriptionAr: product.descriptionAr ?? undefined,
          descriptionEn: product.descriptionEn ?? undefined,
          brandId: product.brandId ?? undefined,
          primaryCategoryId: product.primaryCategoryId ?? undefined,
          categoryIds: product.categoryIds,
          basePrice: product.basePrice,
          compareAtPrice: product.compareAtPrice ?? undefined,
          costPrice: product.costPrice ?? undefined,
          currency: product.currency,
          taxRate: product.taxRate ?? undefined,
          weightGrams: product.weightGrams ?? undefined,
          status: product.status,
          featured: product.featured,
          digital: product.digital,
          seoTitleAr: product.seoTitleAr ?? undefined,
          seoTitleEn: product.seoTitleEn ?? undefined,
          seoDescriptionAr: product.seoDescriptionAr ?? undefined,
          seoDescriptionEn: product.seoDescriptionEn ?? undefined,
          attributeValueIds: product.attributeValueIds,
        }
      : { status: 'DRAFT', basePrice: 0, sku: '', nameAr: '' },
  });

  const save = useMutation({
    mutationFn: (values: ProductWriteRequest) => {
      const body: ProductWriteRequest = {
        ...values,
        images: images.map((url, index) => ({ url, primary: index === 0 })),
      };
      return product
        ? adminApi().products.update(product.id, body)
        : adminApi().products.create(body);
    },
    onSuccess: (saved) => router.push(`/products/${saved.id}`),
  });

  const remove = useMutation({
    mutationFn: () => adminApi().products.remove(product!.id),
    onSuccess: () => router.push('/products'),
  });

  const errors = form.formState.errors;

  return (
    <form
      className="space-y-4"
      onSubmit={form.handleSubmit((values) => save.mutate(values))}
      noValidate
    >
      <Card>
        <CardHeader>
          <CardTitle>Basics</CardTitle>
        </CardHeader>
        <CardBody className="grid gap-4 sm:grid-cols-2">
          <Field id="sku" label="SKU" error={errors.sku?.message} required>
            <Input id="sku" {...form.register('sku')} disabled={!canWrite} />
          </Field>

          <Field
            id="slug"
            label="Slug"
            hint="Leave blank to generate one from the name."
            error={errors.slug?.message}
          >
            <Input id="slug" {...form.register('slug')} disabled={!canWrite} />
          </Field>

          <Field id="nameAr" label="Name (Arabic)" error={errors.nameAr?.message} required>
            <Input id="nameAr" dir="rtl" {...form.register('nameAr')} disabled={!canWrite} />
          </Field>

          <Field id="nameEn" label="Name (English)" error={errors.nameEn?.message}>
            <Input id="nameEn" {...form.register('nameEn')} disabled={!canWrite} />
          </Field>

          <Field id="brandId" label="Brand">
            <Select id="brandId" {...form.register('brandId')} disabled={!canWrite}>
              <option value="">No brand</option>
              {brands.data?.map((brand) => (
                <option key={brand.id} value={brand.id}>
                  {brand.name}
                </option>
              ))}
            </Select>
          </Field>

          <Field id="primaryCategoryId" label="Category">
            <Select
              id="primaryCategoryId"
              {...form.register('primaryCategoryId')}
              disabled={!canWrite}
            >
              <option value="">No category</option>
              {categories.data?.map((category) => (
                <option key={category.id} value={category.id}>
                  {category.name}
                </option>
              ))}
            </Select>
          </Field>
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Pricing</CardTitle>
        </CardHeader>
        <CardBody className="grid gap-4 sm:grid-cols-3">
          <Field id="basePrice" label="Price" error={errors.basePrice?.message} required>
            <Input
              id="basePrice"
              type="number"
              step="0.01"
              min="0"
              disabled={!canWrite}
              {...form.register('basePrice', { valueAsNumber: true })}
            />
          </Field>

          <Field
            id="compareAtPrice"
            label="Compare at"
            hint="Shown struck through; leave blank when not on sale."
            error={errors.compareAtPrice?.message}
          >
            <Input
              id="compareAtPrice"
              type="number"
              step="0.01"
              min="0"
              disabled={!canWrite}
              {...form.register('compareAtPrice', {
                setValueAs: (value) => (value === '' ? undefined : Number(value)),
              })}
            />
          </Field>

          <Field
            id="costPrice"
            label="Cost"
            hint="Internal - never shown to customers."
            error={errors.costPrice?.message}
          >
            <Input
              id="costPrice"
              type="number"
              step="0.01"
              min="0"
              disabled={!canWrite}
              {...form.register('costPrice', {
                setValueAs: (value) => (value === '' ? undefined : Number(value)),
              })}
            />
          </Field>
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Description</CardTitle>
        </CardHeader>
        <CardBody className="grid gap-4 sm:grid-cols-2">
          <Field id="shortDescriptionAr" label="Short description (Arabic)">
            <Textarea
              id="shortDescriptionAr"
              dir="rtl"
              disabled={!canWrite}
              {...form.register('shortDescriptionAr')}
            />
          </Field>
          <Field id="shortDescriptionEn" label="Short description (English)">
            <Textarea
              id="shortDescriptionEn"
              disabled={!canWrite}
              {...form.register('shortDescriptionEn')}
            />
          </Field>
          <Field id="descriptionAr" label="Full description (Arabic)">
            <Textarea
              id="descriptionAr"
              dir="rtl"
              className="min-h-40"
              disabled={!canWrite}
              {...form.register('descriptionAr')}
            />
          </Field>
          <Field id="descriptionEn" label="Full description (English)">
            <Textarea
              id="descriptionEn"
              className="min-h-40"
              disabled={!canWrite}
              {...form.register('descriptionEn')}
            />
          </Field>
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Images</CardTitle>
        </CardHeader>
        <CardBody>
          <ImageUploader images={images} onChange={setImages} disabled={!canWrite} />
        </CardBody>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Publishing</CardTitle>
        </CardHeader>
        <CardBody className="grid gap-4 sm:grid-cols-2">
          <Field id="status" label="Status" required>
            <Select id="status" {...form.register('status')} disabled={!canWrite}>
              <option value="DRAFT">Draft</option>
              <option value="ACTIVE">Active</option>
              <option value="ARCHIVED">Archived</option>
            </Select>
          </Field>

          <label className="flex items-center gap-2 self-end pb-2 text-xs">
            <input type="checkbox" disabled={!canWrite} {...form.register('featured')} />
            Feature on the home page
          </label>
        </CardBody>
      </Card>

      {save.error ? (
        <p role="alert" className="text-sm text-danger-500">
          {save.error instanceof ApiError ? save.error.detail : 'Could not save the product.'}
        </p>
      ) : null}

      {canWrite ? (
        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" loading={save.isPending}>
            {editing ? 'Save changes' : 'Create product'}
          </Button>

          <Button type="button" variant="ghost" onClick={() => router.back()}>
            Cancel
          </Button>

          {editing ? (
            <Button
              type="button"
              variant="ghost"
              className="ms-auto text-danger-500"
              loading={remove.isPending}
              onClick={() => {
                // Soft delete on the server, but irreversible from this screen - worth a confirm.
                if (window.confirm('Archive and delist this product?')) remove.mutate();
              }}
            >
              <Trash2 aria-hidden />
              Delete
            </Button>
          ) : null}
        </div>
      ) : (
        <p className="text-xs text-[var(--muted-foreground)]">
          Your role can view this product but not change it.
        </p>
      )}
    </form>
  );
}
