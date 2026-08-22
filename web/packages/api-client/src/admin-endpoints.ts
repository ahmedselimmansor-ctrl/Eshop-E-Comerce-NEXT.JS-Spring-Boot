import { z } from 'zod';
import { ApiClient, newIdempotencyKey, type RequestOptions } from './http';
import { userSchema } from './schemas/auth';
import { reviewSchema } from './schemas/catalog';
import { orderSchema } from './schemas/commerce';
import {
  adminCustomerListSchema,
  adminProductDetailSchema,
  adminOrderDetailSchema,
  adminOrderListSchema,
  adminProductListSchema,
  adminReviewListSchema,
  adminStockListSchema,
  dashboardSummarySchema,
  lowStockItemSchema,
  orderStatusBreakdownSchema,
  refundResponseSchema,
  revenuePointSchema,
  searchStatusSchema,
  settingRowSchema,
  stockRowSchema,
  topProductSchema,
  uploadTargetSchema,
  zeroResultQuerySchema,
  type AdminOrderQuery,
  type FulfilRequest,
  type ProductStatus,
  type ProductWriteRequest,
  type RefundRequest,
  type ReviewModerationStatus,
  type UserStatus,
  type VariantSpec,
} from './schemas/admin';

/**
 * The admin API.
 *
 * A separate facade from {@link EshopApi} rather than another group on it. The storefront never
 * calls any of this, and keeping the two apart means its bundle never carries the admin schemas —
 * nor gives anyone reading the client a map of the privileged endpoints.
 */
export class EshopAdminApi {
  constructor(private readonly client: ApiClient) {}

  private readonly base = '/api/v1/admin';

  // ----------------------------------------------------------------- dashboard --

  readonly dashboard = {
    /**
     * Headline figures, each paired with the preceding period of the same length.
     *
     * `rangeDays` therefore selects both windows: 30 means "the last 30 days against the 30 before
     * that", which is the comparison an operator actually reasons about.
     */
    summary: (rangeDays = 30, options?: RequestOptions) =>
      this.client.request(`${this.base}/dashboard/summary`, dashboardSummarySchema, {
        query: { rangeDays },
        cache: 'no-store',
        ...options,
      }),

    revenueSeries: (rangeDays = 30) =>
      this.client.request(`${this.base}/dashboard/revenue-series`, z.array(revenuePointSchema), {
        query: { rangeDays },
        cache: 'no-store',
      }),

    topProducts: (rangeDays = 30, limit = 10) =>
      this.client.request(`${this.base}/dashboard/top-products`, z.array(topProductSchema), {
        query: { rangeDays, limit },
        cache: 'no-store',
      }),

    lowStock: (limit = 10) =>
      this.client.request(`${this.base}/dashboard/low-stock`, z.array(lowStockItemSchema), {
        query: { limit },
        cache: 'no-store',
      }),

    recentOrders: (limit = 10) =>
      this.client.request(`${this.base}/dashboard/recent-orders`, adminOrderListSchema, {
        query: { limit },
        cache: 'no-store',
      }),

    orderStatusBreakdown: (rangeDays = 30) =>
      this.client.request(
        `${this.base}/dashboard/order-status-breakdown`,
        orderStatusBreakdownSchema,
        { query: { rangeDays }, cache: 'no-store' },
      ),

    /** Searches that returned nothing — the shortest path to finding a hole in the catalog. */
    zeroResultSearches: (rangeDays = 30, limit = 20) =>
      this.client.request(
        `${this.base}/dashboard/zero-result-searches`,
        z.array(zeroResultQuerySchema),
        { query: { rangeDays, limit }, cache: 'no-store' },
      ),
  };

  // ------------------------------------------------------------------ products --

  readonly products = {
    list: (params: {
      q?: string;
      status?: ProductStatus;
      page?: number;
      size?: number;
      sortBy?: string;
    } = {}) =>
      this.client.request(`${this.base}/products`, adminProductListSchema, {
        query: { ...params },
        cache: 'no-store',
      }),

    /** Returns drafts and archived products too, unlike the storefront's detail endpoint. */
    get: (productId: string) =>
      this.client.request(`${this.base}/products/${productId}`, adminProductDetailSchema, {
        cache: 'no-store',
      }),

    create: (body: ProductWriteRequest) =>
      this.client.request(`${this.base}/products`, adminProductDetailSchema, { method: 'POST', body }),

    update: (productId: string, body: ProductWriteRequest) =>
      this.client.request(`${this.base}/products/${productId}`, adminProductDetailSchema, {
        method: 'PUT',
        body,
      }),

    /** Soft delete — order history keeps pointing at the product, and the slug stays claimed. */
    remove: (productId: string) =>
      this.client.requestVoid(`${this.base}/products/${productId}`, { method: 'DELETE' }),

    bulkStatus: (productIds: string[], status: ProductStatus) =>
      this.client.request(
        `${this.base}/products/bulk-status`,
        z.object({ updated: z.number() }).passthrough(),
        { method: 'PATCH', body: { productIds, status } },
      ),

    addVariant: (productId: string, body: VariantSpec) =>
      this.client.request(`${this.base}/products/${productId}/variants`, adminProductDetailSchema, {
        method: 'POST',
        body,
      }),

    removeVariant: (variantId: string) =>
      this.client.requestVoid(`${this.base}/products/variants/${variantId}`, { method: 'DELETE' }),
  };

  // -------------------------------------------------------------------- orders --

  readonly orders = {
    list: (params: AdminOrderQuery = {}) =>
      this.client.request(`${this.base}/orders`, adminOrderListSchema, {
        query: { ...params },
        cache: 'no-store',
      }),

    get: (orderId: string) =>
      this.client.request(`${this.base}/orders/${orderId}`, adminOrderDetailSchema, {
        cache: 'no-store',
      }),

    setStatus: (orderId: string, status: string, note?: string) =>
      this.client.request(`${this.base}/orders/${orderId}/status`, orderSchema, {
        method: 'POST',
        body: { status, note },
      }),

    cancel: (orderId: string, reason?: string) =>
      this.client.request(`${this.base}/orders/${orderId}/cancel`, orderSchema, {
        method: 'POST',
        body: { reason },
      }),

    fulfil: (orderId: string, body: FulfilRequest) =>
      this.client.request(`${this.base}/orders/${orderId}/fulfil`, orderSchema, {
        method: 'POST',
        body,
      }),

    /**
     * Refunds carry an idempotency key.
     *
     * A retried refund is money leaving twice. The key is generated once per attempt so a lost
     * response can be retried by hand without the risk.
     */
    refund: (orderId: string, body: RefundRequest, idempotencyKey = newIdempotencyKey()) =>
      this.client.request(`${this.base}/orders/${orderId}/refund`, refundResponseSchema, {
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),

    addNote: (orderId: string, note: string) =>
      this.client.requestVoid(`${this.base}/orders/${orderId}/notes`, {
        method: 'POST',
        body: { note },
      }),

    resolveReturn: (returnId: string, status: string, adminNote?: string) =>
      this.client.request(
        `${this.base}/orders/returns/${returnId}/resolve`,
        z.object({ id: z.string(), returnNumber: z.string(), status: z.string() }).passthrough(),
        { method: 'POST', body: { status, adminNote } },
      ),
  };

  // ----------------------------------------------------------------- inventory --

  readonly inventory = {
    list: (params: {
      q?: string;
      warehouseId?: string;
      lowStockOnly?: boolean;
      page?: number;
      size?: number;
    } = {}) =>
      this.client.request(`${this.base}/inventory`, adminStockListSchema, {
        query: { ...params },
        cache: 'no-store',
      }),

    /** Absolute, not a delta — a stock take reports what is on the shelf. */
    adjust: (itemId: string, quantityOnHand: number, reason: string) =>
      this.client.request(`${this.base}/inventory/${itemId}/adjust`, stockRowSchema, {
        method: 'POST',
        body: { quantityOnHand, reason },
      }),

    receive: (itemId: string, quantity: number, note?: string) =>
      this.client.request(`${this.base}/inventory/${itemId}/receive`, stockRowSchema, {
        method: 'POST',
        body: { quantity, note },
      }),
  };

  // ----------------------------------------------------------------- customers --

  readonly customers = {
    list: (params: { q?: string; status?: UserStatus; page?: number; size?: number } = {}) =>
      this.client.request(`${this.base}/customers`, adminCustomerListSchema, {
        query: { ...params },
        cache: 'no-store',
      }),

    get: (userId: string) =>
      this.client.request(`${this.base}/customers/${userId}`, userSchema, { cache: 'no-store' }),

    setStatus: (userId: string, status: UserStatus) =>
      this.client.request(`${this.base}/customers/${userId}/status`, userSchema, {
        method: 'POST',
        body: { status },
      }),

    setRoles: (userId: string, roles: string[]) =>
      this.client.request(`${this.base}/users/${userId}/roles`, userSchema, {
        method: 'POST',
        body: { roles },
      }),
  };

  // ------------------------------------------------------------------- reviews --

  readonly reviews = {
    list: (params: { status?: ReviewModerationStatus; page?: number; size?: number } = {}) =>
      this.client.request(`${this.base}/reviews`, adminReviewListSchema, {
        query: { ...params },
        cache: 'no-store',
      }),

    approve: (reviewId: string) =>
      this.client.request(`${this.base}/reviews/${reviewId}/approve`, reviewSchema, {
        method: 'POST',
      }),

    reject: (reviewId: string, reason: string) =>
      this.client.request(`${this.base}/reviews/${reviewId}/reject`, reviewSchema, {
        method: 'POST',
        body: { reason },
      }),

    reply: (reviewId: string, reply: string) =>
      this.client.request(`${this.base}/reviews/${reviewId}/reply`, reviewSchema, {
        method: 'POST',
        body: { reply },
      }),
  };

  // ------------------------------------------------------------------ settings --

  readonly settings = {
    list: () =>
      this.client.request(`${this.base}/settings`, z.array(settingRowSchema), {
        cache: 'no-store',
      }),

    update: (values: Record<string, unknown>) =>
      this.client.requestVoid(`${this.base}/settings`, { method: 'PUT', body: values }),
  };

  // -------------------------------------------------------------------- search --

  readonly search = {
    status: () =>
      this.client.request(`${this.base}/search/status`, searchStatusSchema, { cache: 'no-store' }),

    reindex: () =>
      this.client.request(
        `${this.base}/search/reindex`,
        z.object({ started: z.boolean() }).passthrough(),
        { method: 'POST' },
      ),
  };

  // --------------------------------------------------------------------- media --

  readonly media = {
    /**
     * Asks for a pre-signed target, then the browser PUTs the file straight to storage.
     *
     * The bytes never pass through the API, which keeps a 10 MB upload off the request thread and
     * out of the pod's memory.
     */
    uploadUrl: (filename: string, contentType: string, folder?: string) =>
      this.client.request(`${this.base}/media/upload-url`, uploadTargetSchema, {
        method: 'POST',
        body: { filename, contentType, folder },
      }),

    remove: (objectKeys: string[]) =>
      this.client.requestVoid(`${this.base}/media`, {
        method: 'DELETE',
        body: { objectKeys },
      }),
  };
}
