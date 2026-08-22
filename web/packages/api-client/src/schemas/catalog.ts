import { z } from 'zod';
import { instantSchema, moneySchema, pageMetaSchema } from './common';

/**
 * Catalog responses are already localised by the API.
 *
 * The server resolves `_ar`/`_en` against `Accept-Language` and applies the "fall back to Arabic
 * when the English name is blank" rule once, rather than shipping both variants to three clients
 * that would each reimplement it slightly differently.
 */

const seoSchema = z.object({
  title: z.string().nullable().optional(),
  description: z.string().nullable().optional(),
});

export const categorySchema: z.ZodType<Category> = z.lazy(() =>
  z.object({
    id: z.string().uuid(),
    slug: z.string(),
    name: z.string(),
    description: z.string().nullable().optional(),
    imageUrl: z.string().nullable().optional(),
    icon: z.string().nullable().optional(),
    position: z.number().int(),
    productCount: z.number().int(),
    parentId: z.string().uuid().nullable().optional(),
    path: z.string().nullable().optional(),
    level: z.number().int(),
    children: z.array(categorySchema),
    seo: seoSchema.nullable().optional(),
  }),
);

export interface Category {
  id: string;
  slug: string;
  name: string;
  description?: string | null;
  imageUrl?: string | null;
  icon?: string | null;
  position: number;
  productCount: number;
  parentId?: string | null;
  path?: string | null;
  level: number;
  children: Category[];
  seo?: { title?: string | null; description?: string | null } | null;
}

export const categoryDetailSchema = z.object({
  category: categorySchema,
  breadcrumb: z.array(categorySchema),
  children: z.array(categorySchema),
});

export const brandSchema = z.object({
  id: z.string().uuid(),
  slug: z.string(),
  name: z.string(),
  description: z.string().nullable().optional(),
  logoUrl: z.string().nullable().optional(),
  website: z.string().nullable().optional(),
  featured: z.boolean(),
});

export type Brand = z.infer<typeof brandSchema>;

export const productCardSchema = z.object({
  id: z.string().uuid(),
  slug: z.string(),
  sku: z.string(),
  name: z.string(),
  shortDescription: z.string().nullable().optional(),
  price: moneySchema,
  compareAtPrice: moneySchema.nullable().optional(),
  discountPercent: z.number().int(),
  imageUrl: z.string().nullable().optional(),
  imageBlurhash: z.string().nullable().optional(),
  brandName: z.string().nullable().optional(),
  brandSlug: z.string().nullable().optional(),
  ratingAvg: z.coerce.number(),
  ratingCount: z.number().int(),
  featured: z.boolean(),
  /** Populated only where the caller asked for availability. */
  inStock: z.boolean().nullable().optional(),
});

export type ProductCard = z.infer<typeof productCardSchema>;

export const productImageSchema = z.object({
  id: z.string().uuid(),
  url: z.string(),
  alt: z.string(),
  primary: z.boolean(),
  width: z.number().int().nullable().optional(),
  height: z.number().int().nullable().optional(),
  /** ~30-byte placeholder that ships inline, so the blur needs no second request. */
  blurhash: z.string().nullable().optional(),
  variantId: z.string().uuid().nullable().optional(),
});

export const axisValueSchema = z.object({
  id: z.string().uuid(),
  code: z.string(),
  value: z.string(),
  hexColor: z.string().nullable().optional(),
  imageUrl: z.string().nullable().optional(),
});

export const variantAxisSchema = z.object({
  code: z.string(),
  name: z.string(),
  type: z.string(),
  values: z.array(axisValueSchema),
});

export const productVariantSchema = z.object({
  id: z.string().uuid(),
  sku: z.string(),
  price: moneySchema,
  compareAtPrice: moneySchema.nullable().optional(),
  discountPercent: z.number().int(),
  label: z.string(),
  imageUrl: z.string().nullable().optional(),
  active: z.boolean(),
  availableQuantity: z.number().int().nullable().optional(),
  inStock: z.boolean(),
  /** `attributeCode → attributeValueCode`; how a selection maps to a variant. */
  selection: z.record(z.string()),
});

export type ProductVariant = z.infer<typeof productVariantSchema>;

export const productDetailSchema = z.object({
  id: z.string().uuid(),
  slug: z.string(),
  sku: z.string(),
  name: z.string(),
  shortDescription: z.string().nullable().optional(),
  /** Sanitised server-side; safe to render, but still rendered through a controlled component. */
  description: z.string().nullable().optional(),
  status: z.enum(['DRAFT', 'ACTIVE', 'ARCHIVED']),
  type: z.enum(['SIMPLE', 'VARIABLE']),
  price: moneySchema,
  compareAtPrice: moneySchema.nullable().optional(),
  discountPercent: z.number().int(),
  currency: z.string(),
  taxRate: z.coerce.number().nullable().optional(),
  weightGrams: z.number().int().nullable().optional(),
  requiresShipping: z.boolean(),
  digital: z.boolean(),
  ratingAvg: z.coerce.number(),
  ratingCount: z.number().int(),
  soldCount: z.number().int(),
  publishedAt: instantSchema.nullable().optional(),
  brand: brandSchema.nullable().optional(),
  category: categorySchema.nullable().optional(),
  breadcrumb: z.array(categorySchema),
  images: z.array(productImageSchema),
  variantAxes: z.array(variantAxisSchema),
  variants: z.array(productVariantSchema),
  specifications: z.array(
    z.object({
      code: z.string(),
      name: z.string(),
      value: z.string(),
      unit: z.string().nullable().optional(),
    }),
  ),
  availableQuantity: z.number().int().nullable().optional(),
  inStock: z.boolean(),
  seo: seoSchema.nullable().optional(),
});

export type ProductDetail = z.infer<typeof productDetailSchema>;

// ------------------------------------------------------------------- facets --

export const facetBucketSchema = z.object({
  key: z.string(),
  label: z.string(),
  count: z.number().int(),
});

export type FacetBucket = z.infer<typeof facetBucketSchema>;

export const facetsSchema = z.object({
  categories: z.array(facetBucketSchema),
  brands: z.array(facetBucketSchema),
  attributes: z.record(z.array(facetBucketSchema)),
  priceRange: z.object({
    min: z.coerce.number().nullable(),
    max: z.coerce.number().nullable(),
  }),
});

export type Facets = z.infer<typeof facetsSchema>;

/**
 * A product listing.
 *
 * `searchIndexUsed === false` means the response came from the PostgreSQL fallback and the facet
 * counts are incomplete - the UI hides the affected filters rather than showing numbers that do not
 * add up.
 */
export const productListSchema = z.object({
  content: z.array(productCardSchema),
  page: pageMetaSchema,
  facets: facetsSchema,
  searchIndexUsed: z.boolean(),
});

export type ProductList = z.infer<typeof productListSchema>;

export const searchResponseSchema = productListSchema.extend({
  query: z.string(),
});

export const suggestionsSchema = z.object({
  products: z.array(
    z.object({
      id: z.string().uuid(),
      slug: z.string(),
      name: z.string(),
      imageUrl: z.string().nullable().optional(),
      price: z.coerce.number().nullable().optional(),
    }),
  ),
  categories: z.array(facetBucketSchema),
  brands: z.array(facetBucketSchema),
  queries: z.array(z.string()),
});

export type Suggestions = z.infer<typeof suggestionsSchema>;

export const reviewSchema = z.object({
  id: z.string().uuid(),
  rating: z.number().int().min(1).max(5),
  title: z.string().nullable().optional(),
  body: z.string().nullable().optional(),
  status: z.enum(['PENDING', 'APPROVED', 'REJECTED']),
  verifiedPurchase: z.boolean(),
  helpfulCount: z.number().int(),
  adminReply: z.string().nullable().optional(),
  adminRepliedAt: instantSchema.nullable().optional(),
  mediaUrls: z.array(z.string()),
  authorName: z.string(),
  authorAvatarUrl: z.string().nullable().optional(),
  createdAt: instantSchema,
});

export type Review = z.infer<typeof reviewSchema>;

export const productReviewsSchema = z.object({
  reviews: z.object({
    content: z.array(reviewSchema),
    page: pageMetaSchema,
  }),
  /** 5→1 star histogram, including stars nobody has given. */
  ratingBreakdown: z.record(z.number().int()),
});

export const reviewRequestSchema = z.object({
  rating: z.number().int().min(1).max(5),
  title: z.string().max(120).optional(),
  body: z.string().max(4000).optional(),
  mediaUrls: z.array(z.string().max(1024)).max(5).optional(),
});

export type ReviewRequest = z.infer<typeof reviewRequestSchema>;

/** Filters for `GET /products`. Attribute facets travel as repeated `attr_<code>` parameters. */
export interface ProductQueryParams {
  q?: string;
  categorySlug?: string;
  brandSlug?: string[];
  minPrice?: number;
  maxPrice?: number;
  minRating?: number;
  inStock?: boolean;
  onSale?: boolean;
  sort?: 'relevance' | 'price_asc' | 'price_desc' | 'newest' | 'best_selling' | 'rating';
  page?: number;
  size?: number;
  attributes?: Record<string, string[]>;
}
