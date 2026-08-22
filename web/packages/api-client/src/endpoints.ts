import { z } from 'zod';
import { ApiClient, newIdempotencyKey, type RequestOptions } from './http';
import {
  addressSchema,
  authResponseSchema,
  userSchema,
  type AddressRequest,
  type LoginRequest,
  type RegisterRequest,
  type UpdateProfileRequest,
} from './schemas/auth';
import {
  brandSchema,
  categoryDetailSchema,
  categorySchema,
  productCardSchema,
  productDetailSchema,
  productListSchema,
  productReviewsSchema,
  searchResponseSchema,
  suggestionsSchema,
  type ProductQueryParams,
  type ReviewRequest,
} from './schemas/catalog';
import {
  cartSchema,
  checkoutQuoteSchema,
  orderListSchema,
  orderSchema,
  paymentInstructionSchema,
  placedOrderSchema,
  shippingOptionSchema,
  wishlistMoveResultSchema,
  wishlistSchema,
  type CheckoutRequest,
} from './schemas/commerce';

/**
 * Typed methods for every endpoint the storefront uses.
 *
 * Grouped by domain rather than exposed as loose functions so a caller has one object to inject and
 * one place to look. Each method pairs a path with the schema its response must satisfy - the two
 * are never separated, which is what keeps the parsing guarantee from being accidentally skipped.
 */
export class EshopApi {
  constructor(private readonly client: ApiClient) {}

  // ------------------------------------------------------------------ auth --

  readonly auth = {
    register: (body: RegisterRequest) =>
      this.client.request('/api/v1/auth/register', authResponseSchema, {
        method: 'POST',
        body,
        skipAuthRefresh: true,
      }),

    login: (body: LoginRequest) =>
      this.client.request('/api/v1/auth/login', authResponseSchema, {
        method: 'POST',
        body,
        skipAuthRefresh: true,
      }),

    /** The refresh token travels as an HttpOnly cookie in the browser; the body stays empty. */
    refresh: (refreshToken?: string) =>
      this.client.request('/api/v1/auth/refresh', authResponseSchema, {
        method: 'POST',
        body: refreshToken ? { refreshToken } : {},
        skipAuthRefresh: true,
      }),

    logout: () =>
      this.client.requestVoid('/api/v1/auth/logout', { method: 'POST', body: {} }),

    forgotPassword: (email: string) =>
      this.client.requestVoid('/api/v1/auth/forgot-password', { method: 'POST', body: { email } }),

    resetPassword: (token: string, newPassword: string) =>
      this.client.requestVoid('/api/v1/auth/reset-password', {
        method: 'POST',
        body: { token, newPassword },
      }),

    verifyEmail: (token: string) =>
      this.client.requestVoid('/api/v1/auth/verify-email', { method: 'POST', body: { token } }),

    resendVerification: (email: string) =>
      this.client.requestVoid('/api/v1/auth/resend-verification', {
        method: 'POST',
        body: { email },
      }),

    /** Redeems the single-use code the OAuth callback put in the URL. */
    exchangeOAuthCode: (code: string) =>
      this.client.request('/api/v1/auth/oauth/exchange', authResponseSchema, {
        method: 'POST',
        body: { code },
        skipAuthRefresh: true,
      }),
  };

  // --------------------------------------------------------------- account --

  readonly account = {
    me: (options?: RequestOptions) => this.client.request('/api/v1/me', userSchema, options),

    updateProfile: (body: UpdateProfileRequest) =>
      this.client.request('/api/v1/me', userSchema, { method: 'PATCH', body }),

    changePassword: (currentPassword: string, newPassword: string) =>
      this.client.requestVoid('/api/v1/me/change-password', {
        method: 'POST',
        body: { currentPassword, newPassword },
      }),

    deleteAccount: () => this.client.requestVoid('/api/v1/me', { method: 'DELETE' }),

    addresses: () => this.client.request('/api/v1/me/addresses', z.array(addressSchema)),

    createAddress: (body: AddressRequest) =>
      this.client.request('/api/v1/me/addresses', addressSchema, { method: 'POST', body }),

    updateAddress: (id: string, body: AddressRequest) =>
      this.client.request(`/api/v1/me/addresses/${id}`, addressSchema, { method: 'PATCH', body }),

    deleteAddress: (id: string) =>
      this.client.requestVoid(`/api/v1/me/addresses/${id}`, { method: 'DELETE' }),

    makeAddressDefault: (id: string, type: 'shipping' | 'billing' = 'shipping') =>
      this.client.request(`/api/v1/me/addresses/${id}/default`, addressSchema, {
        method: 'POST',
        query: { type },
      }),
  };

  // --------------------------------------------------------------- catalog --

  readonly catalog = {
    categoryTree: (options?: RequestOptions) =>
      this.client.request('/api/v1/categories/tree', z.array(categorySchema), {
        // The navigation menu is on every page and the taxonomy changes rarely; revalidating on a
        // timer costs one request a minute instead of one per render.
        next: { revalidate: 300, tags: ['categories'] },
        ...options,
      }),

    category: (slug: string, options?: RequestOptions) =>
      this.client.request(`/api/v1/categories/${slug}`, categoryDetailSchema, {
        next: { revalidate: 300, tags: ['categories'] },
        ...options,
      }),

    brands: (featured = false, options?: RequestOptions) =>
      this.client.request('/api/v1/brands', z.array(brandSchema), {
        query: { featured },
        next: { revalidate: 600, tags: ['brands'] },
        ...options,
      }),

    products: (params: ProductQueryParams = {}, options?: RequestOptions) =>
      this.client.request('/api/v1/products', productListSchema, {
        query: toProductQuery(params),
        ...options,
      }),

    product: (slug: string, options?: RequestOptions) =>
      this.client.request(`/api/v1/products/${slug}`, productDetailSchema, {
        // Never cached: the response embeds live stock, and a cached copy would advertise a
        // sold-out variant as available for the whole TTL.
        cache: 'no-store',
        ...options,
      }),

    related: (productId: string, limit = 12) =>
      this.client.request(`/api/v1/products/${productId}/related`, z.array(productCardSchema), {
        query: { limit },
        next: { revalidate: 300 },
      }),

    frequentlyBought: (productId: string, limit = 4) =>
      this.client.request(
        `/api/v1/products/${productId}/frequently-bought`,
        z.array(productCardSchema),
        { query: { limit }, next: { revalidate: 600 } },
      ),

    featured: (limit = 12) =>
      this.client.request('/api/v1/products/featured', z.array(productCardSchema), {
        query: { limit },
        next: { revalidate: 300, tags: ['products'] },
      }),

    newArrivals: (limit = 12) =>
      this.client.request('/api/v1/products/new-arrivals', z.array(productCardSchema), {
        query: { limit },
        next: { revalidate: 300, tags: ['products'] },
      }),

    bestSellers: (limit = 12) =>
      this.client.request('/api/v1/products/best-sellers', z.array(productCardSchema), {
        query: { limit },
        next: { revalidate: 300, tags: ['products'] },
      }),

    reviews: (productId: string, page = 0, size = 10, sort = 'newest', rating?: number) =>
      this.client.request(`/api/v1/products/${productId}/reviews`, productReviewsSchema, {
        query: { page, size, sort, rating },
      }),

    submitReview: (productId: string, body: ReviewRequest) =>
      this.client.request(
        `/api/v1/products/${productId}/reviews`,
        productReviewsSchema.shape.reviews.shape.content.element,
        { method: 'POST', body },
      ),
  };

  // ---------------------------------------------------------------- search --

  readonly search = {
    query: (q: string, params: ProductQueryParams = {}) =>
      this.client.request('/api/v1/search', searchResponseSchema, {
        query: { q, ...toProductQuery(params) },
        cache: 'no-store',
      }),

    suggest: (q: string, limit = 8, signal?: AbortSignal) =>
      this.client.request('/api/v1/search/suggest', suggestionsSchema, {
        query: { q, limit },
        // Aborted and replaced on every keystroke; without the signal, a slow earlier request can
        // resolve last and overwrite fresher suggestions.
        signal,
      }),

    popular: (limit = 10) =>
      this.client.request('/api/v1/search/popular', z.array(z.string()), {
        query: { limit },
        next: { revalidate: 900 },
      }),

    /** Fire-and-forget relevance feedback; failures are deliberately ignored. */
    recordClick: (query: string, productId: string) =>
      this.client
        .requestVoid('/api/v1/search/click', { method: 'POST', body: { query, productId } })
        .catch(() => undefined),
  };

  // ------------------------------------------------------------------ cart --

  readonly cart = {
    get: () => this.client.request('/api/v1/cart', cartSchema, { cache: 'no-store' }),

    addItem: (productId: string, quantity: number, variantId?: string) =>
      this.client.request('/api/v1/cart/items', cartSchema, {
        method: 'POST',
        body: { productId, variantId, quantity },
      }),

    updateItem: (itemId: string, quantity: number) =>
      this.client.request(`/api/v1/cart/items/${itemId}`, cartSchema, {
        method: 'PATCH',
        body: { quantity },
      }),

    removeItem: (itemId: string) =>
      this.client.request(`/api/v1/cart/items/${itemId}`, cartSchema, { method: 'DELETE' }),

    clear: () => this.client.request('/api/v1/cart', cartSchema, { method: 'DELETE' }),

    applyCoupon: (code: string) =>
      this.client.request('/api/v1/cart/coupon', cartSchema, { method: 'POST', body: { code } }),

    removeCoupon: () =>
      this.client.request('/api/v1/cart/coupon', cartSchema, { method: 'DELETE' }),

    /** Call immediately after login to fold the guest cart into the customer's. */
    merge: (sessionId: string) =>
      this.client.request('/api/v1/cart/merge', cartSchema, {
        method: 'POST',
        body: { sessionId },
      }),

    validate: () => this.client.request('/api/v1/cart/validate', cartSchema, { method: 'POST' }),

    reconcile: () => this.client.request('/api/v1/cart/reconcile', cartSchema, { method: 'POST' }),
  };

  // -------------------------------------------------------------- checkout --

  readonly checkout = {
    shippingMethods: (governorate: string, subtotal = 0, totalGrams = 0) =>
      this.client.request('/api/v1/checkout/shipping-methods', z.array(shippingOptionSchema), {
        query: { governorate, subtotal, totalGrams },
      }),

    quote: (params: {
      governorate: string;
      shippingMethodId?: string;
      couponCode?: string;
      paymentProvider?: string;
    }) =>
      this.client.request('/api/v1/checkout/quote', checkoutQuoteSchema, {
        method: 'POST',
        query: params,
      }),

    /**
     * Places the order.
     *
     * The idempotency key defaults to a fresh one, but a caller retrying a failed attempt should
     * pass the *original* key - that is what makes the retry safe. Generating a new key on retry
     * gives up the entire guarantee.
     */
    placeOrder: (body: CheckoutRequest, idempotencyKey: string = newIdempotencyKey()) =>
      this.client.request('/api/v1/checkout/orders', placedOrderSchema, {
        method: 'POST',
        body,
        headers: { 'Idempotency-Key': idempotencyKey },
      }),
  };

  // ---------------------------------------------------------------- orders --

  readonly orders = {
    list: (page = 0, size = 20, status?: string) =>
      this.client.request('/api/v1/me/orders', orderListSchema, {
        query: { page, size, status },
        cache: 'no-store',
      }),

    detail: (orderNumber: string) =>
      this.client.request(`/api/v1/me/orders/${orderNumber}`, orderSchema, { cache: 'no-store' }),

    cancel: (orderNumber: string, reason: string) =>
      this.client.request(`/api/v1/me/orders/${orderNumber}/cancel`, orderSchema, {
        method: 'POST',
        body: { reason },
      }),

    /** Guest tracking needs both the number and the email it was placed with. */
    track: (orderNumber: string, email: string) =>
      this.client.request(`/api/v1/orders/track/${orderNumber}`, orderSchema, {
        query: { email },
        cache: 'no-store',
      }),

    requestReturn: (
      orderNumber: string,
      quantitiesByItemId: Record<string, number>,
      reason: string,
      note?: string,
    ) =>
      this.client.request(
        `/api/v1/me/orders/${orderNumber}/returns`,
        z.object({
          id: z.string().uuid(),
          returnNumber: z.string(),
          status: z.string(),
          reason: z.string(),
          totalQuantity: z.number().int(),
          createdAt: z.string(),
        }),
        { method: 'POST', body: { quantitiesByItemId, reason, note } },
      ),
  };

  // -------------------------------------------------------------- payments --

  readonly payments = {
    /**
     * Polled after the customer returns from a payment page.
     *
     * The provider's callback and the browser redirect race each other and the redirect usually
     * wins, so this asks the provider directly rather than waiting for the webhook.
     */
    status: (paymentId: string) =>
      this.client.request(`/api/v1/payments/${paymentId}/status`, paymentInstructionSchema, {
        cache: 'no-store',
      }),

    retry: (paymentId: string) =>
      this.client.request(`/api/v1/payments/${paymentId}/retry`, paymentInstructionSchema, {
        method: 'POST',
      }),
  };

  // -------------------------------------------------------------- wishlist --

  /**
   * Saved items. Signed-in only - a wishlist outlives the session it was created in, so anchoring it
   * to a guest cookie would quietly lose it.
   *
   * Every mutation returns the whole list rather than an acknowledgement, which lets the caller
   * write the result straight into the query cache instead of refetching.
   */
  readonly wishlist = {
    get: (options?: RequestOptions) =>
      this.client.request('/api/v1/wishlist', wishlistSchema, {
        cache: 'no-store',
        ...options,
      }),

    /** Idempotent: saving something already saved succeeds and changes nothing. */
    add: (productId: string, variantId?: string | null) =>
      this.client.request('/api/v1/wishlist/items', wishlistSchema, {
        method: 'POST',
        body: { productId, variantId: variantId ?? null },
      }),

    remove: (itemId: string) =>
      this.client.request(`/api/v1/wishlist/items/${itemId}`, wishlistSchema, {
        method: 'DELETE',
      }),

    /** For a listing page's heart toggle, which knows the product but not the saved-item id. */
    removeProduct: (productId: string, variantId?: string | null) =>
      this.client.request(`/api/v1/wishlist/products/${productId}`, wishlistSchema, {
        method: 'DELETE',
        query: variantId ? { variantId } : undefined,
      }),

    clear: () =>
      this.client.request('/api/v1/wishlist', wishlistSchema, { method: 'DELETE' }),

    /** Fails, leaving the item saved, if the product is no longer purchasable. */
    moveToCart: (itemId: string, quantity = 1) =>
      this.client.request(
        `/api/v1/wishlist/items/${itemId}/move-to-cart`,
        wishlistMoveResultSchema,
        { method: 'POST', body: { quantity } },
      ),

    /** Publishing mints a fresh token, so republishing revokes the link already sent. */
    setPublic: (isPublic: boolean) =>
      this.client.request('/api/v1/wishlist/share', wishlistSchema, {
        method: 'PUT',
        body: { isPublic },
      }),

    /** Anonymous read of someone else's shared list. `shareToken` comes back null. */
    shared: (token: string, options?: RequestOptions) =>
      this.client.request(`/api/v1/wishlist/shared/${token}`, wishlistSchema, options),
  };
}

/** Flattens attribute filters into the repeated `attr_<code>` parameters the API expects. */
function toProductQuery(params: ProductQueryParams): Record<string, unknown> {
  const { attributes, ...rest } = params;
  const query: Record<string, unknown> = { ...rest };

  if (attributes) {
    for (const [code, values] of Object.entries(attributes)) {
      if (values.length > 0) {
        query[`attr_${code}`] = values;
      }
    }
  }
  return query;
}
