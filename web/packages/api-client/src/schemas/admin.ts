import { z } from 'zod';
import { productDetailSchema, reviewSchema } from './catalog';
import { instantSchema, moneySchema, pageMetaSchema } from './common';
import { orderSchema, orderStatusSchema, orderSummarySchema, paymentStatusSchema } from './commerce';
import { userSchema } from './auth';

/**
 * The admin API contract.
 *
 * Kept in its own module rather than folded into the storefront schemas: the storefront bundle has
 * no reason to carry the shape of a refund request, and the split keeps the tree-shaking honest.
 */

// ------------------------------------------------------------------ dashboard --

/**
 * A headline figure with the same figure for the preceding period.
 *
 * The comparison is not decoration. "142,000 EGP this week" tells an operator nothing on its own;
 * "142,000, up 12%" is the whole reason the tile exists, so the API returns both and the UI is never
 * in a position to render half the story.
 */
export const metricSchema = z.object({
  current: moneySchema,
  previous: moneySchema,

  /**
   * Absent when the previous period was zero.
   *
   * Growth from nothing has no percentage - the API returns null rather than inventing "+100%", and
   * the tile renders no change indicator at all. Requiring a number here would reject the entire
   * dashboard payload on the most ordinary case there is: a store's first week.
   */
  changePercent: z.number().nullable().optional(),
});

export type Metric = z.infer<typeof metricSchema>;

export const countMetricSchema = z.object({
  current: z.number(),
  previous: z.number(),
  /** Null when the previous period was zero - see {@link metricSchema}. */
  changePercent: z.number().nullable().optional(),
});

export type CountMetric = z.infer<typeof countMetricSchema>;

export const dashboardSummarySchema = z.object({
  revenue: metricSchema,
  orders: countMetricSchema,
  averageOrderValue: metricSchema,
  newCustomers: countMetricSchema,

  /** Queues that need someone to act, rather than trends to read. */
  awaitingPayment: z.number(),
  awaitingFulfilment: z.number(),
  pendingReviews: z.number(),
  outOfStockSkus: z.number(),
  failedPayments: z.number(),
});

export type DashboardSummary = z.infer<typeof dashboardSummarySchema>;

export const revenuePointSchema = z.object({
  at: instantSchema,
  revenue: moneySchema,
  orders: z.number(),
});

export type RevenuePoint = z.infer<typeof revenuePointSchema>;

export const topProductSchema = z.object({
  productId: z.string().uuid(),
  name: z.string(),
  imageUrl: z.string().nullable().optional(),
  unitsSold: z.number(),
  revenue: moneySchema,
});

export type TopProduct = z.infer<typeof topProductSchema>;

export const lowStockItemSchema = z.object({
  inventoryItemId: z.string().uuid(),
  productId: z.string().uuid(),
  productName: z.string(),
  sku: z.string(),
  available: z.number(),
  reorderLevel: z.number(),
});

export type LowStockItem = z.infer<typeof lowStockItemSchema>;

/** Searches that returned nothing - the most direct signal of a gap in the catalog. */
export const zeroResultQuerySchema = z.object({
  query: z.string(),
  searches: z.number(),
});

export type ZeroResultQuery = z.infer<typeof zeroResultQuerySchema>;

export const orderStatusBreakdownSchema = z.record(z.string(), z.number());

export type OrderStatusBreakdown = z.infer<typeof orderStatusBreakdownSchema>;

// ------------------------------------------------------------------ inventory --

export const stockRowSchema = z.object({
  id: z.string().uuid(),
  productId: z.string().uuid(),
  variantId: z.string().uuid().nullable().optional(),
  productName: z.string(),
  sku: z.string(),
  warehouse: z.string().nullable().optional(),
  onHand: z.number(),
  reserved: z.number(),
  /** On-hand minus reserved - the figure that decides whether a customer can actually buy. */
  available: z.number(),
  reorderLevel: z.number(),
  lowStock: z.boolean(),
});

export type StockRow = z.infer<typeof stockRowSchema>;

// --------------------------------------------------------------------- orders --

export const paymentAttemptSchema = z.object({
  id: z.string().uuid(),
  provider: z.string(),
  method: z.string().nullable().optional(),
  status: z.string(),
  amount: moneySchema,
  cardBrand: z.string().nullable().optional(),
  cardLast4: z.string().nullable().optional(),
  failureReason: z.string().nullable().optional(),
  createdAt: instantSchema,
});

export type PaymentAttempt = z.infer<typeof paymentAttemptSchema>;

export const returnSummarySchema = z.object({
  id: z.string().uuid(),
  returnNumber: z.string(),
  status: z.string(),
  reason: z.string().nullable().optional(),
  totalQuantity: z.number(),
});

export type ReturnSummary = z.infer<typeof returnSummarySchema>;

/**
 * An order as staff see it: the customer's view plus everything deliberately withheld from them -
 * internal notes, the originating IP, and every payment attempt including the failed ones.
 */
export const adminOrderDetailSchema = z.object({
  order: orderSchema,
  adminNote: z.string().nullable().optional(),
  ip: z.string().nullable().optional(),
  userAgent: z.string().nullable().optional(),
  payments: z.array(paymentAttemptSchema),
  returns: z.array(returnSummarySchema),
});

export type AdminOrderDetail = z.infer<typeof adminOrderDetailSchema>;

export const refundResponseSchema = z.object({
  id: z.string().uuid(),
  amount: moneySchema,
  status: z.string(),
  completedAt: instantSchema.nullable().optional(),
});

export type RefundResponse = z.infer<typeof refundResponseSchema>;

// ------------------------------------------------------------------- settings --

export const settingRowSchema = z.object({
  key: z.string(),
  value: z.unknown(),
  description: z.string().nullable().optional(),
  publicSetting: z.boolean(),
});

export type SettingRow = z.infer<typeof settingRowSchema>;

export const searchStatusSchema = z.record(z.string(), z.unknown());

// --------------------------------------------------------------------- media --

export const uploadTargetSchema = z.object({
  /** Pre-signed; the browser PUTs the file straight to storage and never through the API. */
  uploadUrl: z.string(),
  objectKey: z.string(),
  publicUrl: z.string(),
  expiresAt: instantSchema,
  requiredContentType: z.string(),
});

export type UploadTarget = z.infer<typeof uploadTargetSchema>;

// ------------------------------------------------------------------- requests --

export const productStatusSchema = z.enum(['DRAFT', 'ACTIVE', 'ARCHIVED']);
export type ProductStatus = z.infer<typeof productStatusSchema>;

export const imageSpecSchema = z.object({
  url: z.string().min(1),
  objectKey: z.string().optional(),
  altAr: z.string().optional(),
  altEn: z.string().optional(),
  width: z.number().int().optional(),
  height: z.number().int().optional(),
  blurhash: z.string().optional(),
  primary: z.boolean().optional(),
});

/**
 * The product write form.
 *
 * Prices are numbers here and `Money` everywhere in responses: the API takes a decimal and decides
 * the currency itself, so a client cannot submit a price in a currency the store does not sell in.
 */
export const productWriteSchema = z.object({
  sku: z.string().min(1).max(64),
  slug: z.string().max(180).optional(),
  nameAr: z.string().min(1).max(255),
  nameEn: z.string().max(255).optional(),
  shortDescriptionAr: z.string().max(600).optional(),
  shortDescriptionEn: z.string().max(600).optional(),
  descriptionAr: z.string().optional(),
  descriptionEn: z.string().optional(),
  brandId: z.string().uuid().nullable().optional(),
  primaryCategoryId: z.string().uuid().nullable().optional(),
  categoryIds: z.array(z.string().uuid()).optional(),
  basePrice: z.number().nonnegative(),
  compareAtPrice: z.number().nonnegative().nullable().optional(),
  costPrice: z.number().nonnegative().nullable().optional(),
  currency: z.string().length(3).optional(),
  taxRate: z.number().min(0).max(1).optional(),
  weightGrams: z.number().int().nonnegative().nullable().optional(),
  lengthMm: z.number().int().nonnegative().nullable().optional(),
  widthMm: z.number().int().nonnegative().nullable().optional(),
  heightMm: z.number().int().nonnegative().nullable().optional(),
  featured: z.boolean().optional(),
  digital: z.boolean().optional(),
  status: productStatusSchema,
  seoTitleAr: z.string().max(200).optional(),
  seoTitleEn: z.string().max(200).optional(),
  seoDescriptionAr: z.string().max(400).optional(),
  seoDescriptionEn: z.string().max(400).optional(),
  images: z.array(imageSpecSchema).optional(),
  attributeValueIds: z.array(z.string().uuid()).optional(),
});

export type ProductWriteRequest = z.infer<typeof productWriteSchema>;

export const variantSpecSchema = z.object({
  sku: z.string().min(1).max(64),
  barcode: z.string().optional(),
  price: z.number().nonnegative(),
  compareAtPrice: z.number().nonnegative().nullable().optional(),
  costPrice: z.number().nonnegative().nullable().optional(),
  weightGrams: z.number().int().nonnegative().nullable().optional(),
  imageUrl: z.string().optional(),
  position: z.number().int().optional(),
  active: z.boolean().optional(),
  attributeValueIds: z.array(z.string().uuid()).min(1),
});

export type VariantSpec = z.infer<typeof variantSpecSchema>;

export const fulfilRequestSchema = z.object({
  quantitiesByItemId: z.record(z.string().uuid(), z.number().int().positive()),
  carrier: z.string().min(1),
  trackingNumber: z.string().optional(),
  trackingUrl: z.string().optional(),
});

export type FulfilRequest = z.infer<typeof fulfilRequestSchema>;

export const refundRequestSchema = z.object({
  amount: z.number().positive(),
  reason: z.string().min(1),
  note: z.string().optional(),
});

export type RefundRequest = z.infer<typeof refundRequestSchema>;

export const userStatusSchema = z.enum(['ACTIVE', 'SUSPENDED', 'DELETED']);
export type UserStatus = z.infer<typeof userStatusSchema>;

export const returnStatusSchema = z.enum([
  'REQUESTED',
  'APPROVED',
  'REJECTED',
  'RECEIVED',
  'REFUNDED',
]);

export const reviewModerationStatusSchema = z.enum(['PENDING', 'APPROVED', 'REJECTED']);
export type ReviewModerationStatus = z.infer<typeof reviewModerationStatusSchema>;

/**
 * A product as the editor sees it - raw and per-language.
 *
 * Deliberately not `productDetailSchema`: that one resolves `name` and `description` for a visitor's
 * locale, and prefilling both language fields from one resolved string would overwrite Arabic with
 * English the first time anyone saved.
 */
export const adminProductDetailSchema = z.object({
  id: z.string().uuid(),
  sku: z.string(),
  slug: z.string(),

  nameAr: z.string(),
  nameEn: z.string().nullable().optional(),
  shortDescriptionAr: z.string().nullable().optional(),
  shortDescriptionEn: z.string().nullable().optional(),
  descriptionAr: z.string().nullable().optional(),
  descriptionEn: z.string().nullable().optional(),

  brandId: z.string().uuid().nullable().optional(),
  brandName: z.string().nullable().optional(),
  primaryCategoryId: z.string().uuid().nullable().optional(),
  primaryCategoryName: z.string().nullable().optional(),
  categoryIds: z.array(z.string().uuid()),

  basePrice: z.coerce.number(),
  compareAtPrice: z.coerce.number().nullable().optional(),
  costPrice: z.coerce.number().nullable().optional(),
  currency: z.string(),
  taxRate: z.coerce.number().nullable().optional(),

  weightGrams: z.number().int().nullable().optional(),
  lengthMm: z.number().int().nullable().optional(),
  widthMm: z.number().int().nullable().optional(),
  heightMm: z.number().int().nullable().optional(),

  status: productStatusSchema,
  type: z.string(),
  featured: z.boolean(),
  digital: z.boolean(),
  requiresShipping: z.boolean(),

  seoTitleAr: z.string().nullable().optional(),
  seoTitleEn: z.string().nullable().optional(),
  seoDescriptionAr: z.string().nullable().optional(),
  seoDescriptionEn: z.string().nullable().optional(),

  images: z.array(
    z.object({
      id: z.string().uuid(),
      url: z.string(),
      objectKey: z.string().nullable().optional(),
      altAr: z.string().nullable().optional(),
      altEn: z.string().nullable().optional(),
      width: z.number().int().nullable().optional(),
      height: z.number().int().nullable().optional(),
      blurhash: z.string().nullable().optional(),
      primary: z.boolean(),
      position: z.number().int(),
    }),
  ),

  variants: z.array(
    z.object({
      id: z.string().uuid(),
      sku: z.string(),
      barcode: z.string().nullable().optional(),
      price: z.coerce.number(),
      compareAtPrice: z.coerce.number().nullable().optional(),
      costPrice: z.coerce.number().nullable().optional(),
      weightGrams: z.number().int().nullable().optional(),
      imageUrl: z.string().nullable().optional(),
      position: z.number().int(),
      active: z.boolean(),
      attributeValueIds: z.array(z.string().uuid()),
      label: z.string().nullable().optional(),
    }),
  ),

  attributeValueIds: z.array(z.string().uuid()),
  publishedAt: instantSchema.nullable().optional(),
  updatedAt: instantSchema.nullable().optional(),
});

export type AdminProductDetail = z.infer<typeof adminProductDetailSchema>;

/**
 * A row in the admin product list.
 *
 * Not the storefront's product card: this one carries `status`, because showing drafts and archived
 * products next to live ones is the entire point of the screen.
 */
export const adminProductRowSchema = z.object({
  id: z.string().uuid(),
  sku: z.string(),
  slug: z.string(),
  name: z.string(),
  brandName: z.string().nullable().optional(),
  price: moneySchema,
  compareAtPrice: moneySchema.nullable().optional(),
  status: productStatusSchema,
  type: z.enum(['SIMPLE', 'VARIABLE', 'DIGITAL', 'BUNDLE']).catch('SIMPLE'),
  featured: z.boolean(),
  imageUrl: z.string().nullable().optional(),
  variantCount: z.number().int(),
  /** Sellable units across all warehouses. */
  available: z.number().int().nullable().optional(),
  updatedAt: instantSchema.nullable().optional(),
});

export type AdminProductRow = z.infer<typeof adminProductRowSchema>;

// -------------------------------------------------------------- paged results --

/** `PageResponse<T>` is generic on the server; each list gets its own concrete schema here. */
function paged<T extends z.ZodTypeAny>(item: T) {
  return z.object({ content: z.array(item), page: pageMetaSchema });
}

export const adminProductListSchema = paged(adminProductRowSchema);
export const adminOrderListSchema = paged(orderSummarySchema);
export const adminCustomerListSchema = paged(userSchema);
export const adminStockListSchema = paged(stockRowSchema);
export const adminReviewListSchema = paged(reviewSchema);

export type AdminProductList = z.infer<typeof adminProductListSchema>;
export type AdminOrderList = z.infer<typeof adminOrderListSchema>;
export type AdminCustomerList = z.infer<typeof adminCustomerListSchema>;
export type AdminStockList = z.infer<typeof adminStockListSchema>;
export type AdminReviewList = z.infer<typeof adminReviewListSchema>;

/** Query parameters shared by the admin order list and the exports built from it. */
export interface AdminOrderQuery {
  q?: string;
  status?: z.infer<typeof orderStatusSchema>;
  paymentStatus?: z.infer<typeof paymentStatusSchema>;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export { productDetailSchema };
