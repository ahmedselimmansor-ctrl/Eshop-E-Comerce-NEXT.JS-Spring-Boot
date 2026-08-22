import { z } from 'zod';
import { productCardSchema } from './catalog';
import { instantSchema, moneySchema, pageMetaSchema } from './common';

// ---------------------------------------------------------------------- cart --

export const cartItemSchema = z.object({
  id: z.string().uuid(),
  productId: z.string().uuid(),
  variantId: z.string().uuid().nullable().optional(),
  slug: z.string().nullable().optional(),
  name: z.string().nullable().optional(),
  variantLabel: z.string().nullable().optional(),
  sku: z.string().nullable().optional(),
  imageUrl: z.string().nullable().optional(),
  unitPrice: moneySchema,
  quantity: z.number().int(),
  lineTotal: moneySchema,
  /** Sellable units right now — the cap for the quantity stepper. */
  availableQuantity: z.number().int(),
  inStock: z.boolean(),
  /** The catalog price moved since this was added; `currentPrice` is the new figure. */
  priceChanged: z.boolean(),
  currentPrice: moneySchema,
});

export type CartItem = z.infer<typeof cartItemSchema>;

export const cartTotalsSchema = z.object({
  subtotal: moneySchema,
  discount: moneySchema,
  tax: moneySchema,
  shipping: moneySchema,
  grandTotal: moneySchema,
});

/** Problems found by `POST /cart/validate`, reported together so they can be fixed in one pass. */
export const cartIssueSchema = z.object({
  itemId: z.string().uuid(),
  type: z.enum(['UNAVAILABLE', 'OUT_OF_STOCK', 'QUANTITY_REDUCED', 'PRICE_CHANGED']),
  available: z.number().int(),
  currentPrice: moneySchema.nullable().optional(),
});

export type CartIssue = z.infer<typeof cartIssueSchema>;

export const cartSchema = z.object({
  id: z.string().uuid(),
  currency: z.string(),
  itemCount: z.number().int(),
  items: z.array(cartItemSchema),
  coupon: z
    .object({
      code: z.string(),
      type: z.string().nullable().optional(),
      discount: moneySchema,
      freeShipping: z.boolean(),
    })
    .nullable()
    .optional(),
  totals: cartTotalsSchema,
  issues: z.array(cartIssueSchema),
});

export type Cart = z.infer<typeof cartSchema>;

/** Note the absence of a price field — the server looks it up. A client price is a client discount. */
export const addCartItemSchema = z.object({
  productId: z.string().uuid(),
  variantId: z.string().uuid().optional(),
  quantity: z.number().int().min(1).max(999),
});

// ------------------------------------------------------------------ checkout --

export const shippingOptionSchema = z.object({
  id: z.string().uuid(),
  code: z.string(),
  name: z.string(),
  description: z.string().nullable().optional(),
  cost: moneySchema,
  estimatedDaysMin: z.number().int().nullable().optional(),
  estimatedDaysMax: z.number().int().nullable().optional(),
  supportsCod: z.boolean(),
  free: z.boolean(),
  /** Order value at which this method becomes free — drives the "spend X more" nudge. */
  freeShippingThreshold: moneySchema.nullable().optional(),
});

export type ShippingOption = z.infer<typeof shippingOptionSchema>;

export const checkoutQuoteSchema = z.object({
  currency: z.string(),
  subtotal: moneySchema,
  discount: moneySchema,
  tax: moneySchema,
  shipping: moneySchema,
  codFee: moneySchema,
  grandTotal: moneySchema,
  shippingOptions: z.array(shippingOptionSchema),
  appliedCoupon: z.string().nullable().optional(),
  couponRejectionCode: z.string().nullable().optional(),
});

export type CheckoutQuote = z.infer<typeof checkoutQuoteSchema>;

export const paymentProviderSchema = z.enum(['PAYMOB', 'FAWRY', 'COD']);
export type PaymentProviderType = z.infer<typeof paymentProviderSchema>;

export const paymentMethodSchema = z.enum(['CARD', 'WALLET', 'INSTALLMENT', 'KIOSK', 'COD']);
export type PaymentMethod = z.infer<typeof paymentMethodSchema>;

export const checkoutRequestSchema = z
  .object({
    shippingAddressId: z.string().uuid().optional(),
    shippingAddress: z.record(z.unknown()).optional(),
    billingAddressId: z.string().uuid().optional(),
    shippingMethodId: z.string().uuid(),
    paymentProvider: paymentProviderSchema,
    paymentMethod: paymentMethodSchema,
    couponCode: z.string().max(40).optional(),
    customerNote: z.string().max(500).optional(),
    guestEmail: z.string().email().max(191).optional(),
    guestPhone: z.string().optional(),
  })
  .refine((data) => data.shippingAddressId || data.shippingAddress, {
    message: 'عنوان الشحن مطلوب',
    path: ['shippingAddressId'],
  });

export type CheckoutRequest = z.infer<typeof checkoutRequestSchema>;

/**
 * What the customer must do next, discriminated by `provider`.
 *
 * Paymob populates `iframeUrl`, Fawry populates `referenceCode` and `expiresAt`, and cash on
 * delivery populates neither — the UI switches on `provider` and renders the matching step.
 */
export const paymentInstructionSchema = z.object({
  paymentId: z.string().uuid(),
  provider: paymentProviderSchema,
  method: paymentMethodSchema,
  status: z.enum([
    'PENDING',
    'AUTHORIZED',
    'CAPTURED',
    'FAILED',
    'CANCELLED',
    'EXPIRED',
    'REFUNDED',
    'PARTIALLY_REFUNDED',
  ]),
  amount: moneySchema,
  iframeUrl: z.string().nullable().optional(),
  redirectUrl: z.string().nullable().optional(),
  referenceCode: z.string().nullable().optional(),
  expiresAt: instantSchema.nullable().optional(),
  actionRequired: z.boolean(),
});

export type PaymentInstruction = z.infer<typeof paymentInstructionSchema>;

// -------------------------------------------------------------------- orders --

/** Payment state, tracked separately from the order's own status. */
export const paymentStatusSchema = z.enum([
  'PENDING',
  'AUTHORIZED',
  'PAID',
  'FAILED',
  'REFUNDED',
  'PARTIALLY_REFUNDED',
]);

export type PaymentStatus = z.infer<typeof paymentStatusSchema>;

export const orderStatusSchema = z.enum([
  'PENDING_PAYMENT',
  'PAID',
  'PROCESSING',
  'SHIPPED',
  'DELIVERED',
  'CANCELLED',
  'REFUNDED',
  'PARTIALLY_REFUNDED',
  'FAILED',
]);

export type OrderStatus = z.infer<typeof orderStatusSchema>;

export const orderItemSchema = z.object({
  id: z.string().uuid(),
  productId: z.string().uuid().nullable().optional(),
  variantId: z.string().uuid().nullable().optional(),
  slug: z.string().nullable().optional(),
  sku: z.string(),
  name: z.string(),
  variantLabel: z.string().nullable().optional(),
  imageUrl: z.string().nullable().optional(),
  unitPrice: moneySchema,
  quantity: z.number().int(),
  discount: moneySchema,
  tax: moneySchema,
  lineTotal: moneySchema,
  fulfilledQuantity: z.number().int(),
  refundedQuantity: z.number().int(),
});

export const orderSchema = z.object({
  id: z.string().uuid(),
  orderNumber: z.string(),
  status: orderStatusSchema,
  paymentStatus: paymentStatusSchema,
  fulfillmentStatus: z.enum([
    'UNFULFILLED',
    'PARTIALLY_FULFILLED',
    'FULFILLED',
    'RETURNED',
  ]),
  currency: z.string(),
  items: z.array(orderItemSchema),
  totals: z.object({
    subtotal: moneySchema,
    discount: moneySchema,
    tax: moneySchema,
    shipping: moneySchema,
    codFee: moneySchema,
    grandTotal: moneySchema,
  }),
  couponCode: z.string().nullable().optional(),
  /** Frozen at placement — not the customer's current address book entry. */
  shippingAddress: z.record(z.unknown()),
  billingAddress: z.record(z.unknown()).nullable().optional(),
  shippingMethodName: z.string().nullable().optional(),
  customerNote: z.string().nullable().optional(),
  timeline: z.array(
    z.object({
      status: orderStatusSchema,
      note: z.string().nullable().optional(),
      at: instantSchema,
      by: z.string(),
    }),
  ),
  shipments: z.array(
    z.object({
      id: z.string().uuid(),
      carrier: z.string().nullable().optional(),
      trackingNumber: z.string().nullable().optional(),
      trackingUrl: z.string().nullable().optional(),
      status: z.string(),
      shippedAt: instantSchema.nullable().optional(),
      deliveredAt: instantSchema.nullable().optional(),
    }),
  ),
  cancellable: z.boolean(),
  placedAt: instantSchema,
  paidAt: instantSchema.nullable().optional(),
  shippedAt: instantSchema.nullable().optional(),
  deliveredAt: instantSchema.nullable().optional(),
  cancelledAt: instantSchema.nullable().optional(),
});

export type Order = z.infer<typeof orderSchema>;

export const orderSummarySchema = z.object({
  id: z.string().uuid(),
  orderNumber: z.string(),
  status: orderStatusSchema,
  paymentStatus: z.string(),
  itemCount: z.number().int(),
  grandTotal: moneySchema,
  previewImageUrl: z.string().nullable().optional(),
  previewName: z.string().nullable().optional(),
  cancellable: z.boolean(),
  placedAt: instantSchema,
});

export type OrderSummary = z.infer<typeof orderSummarySchema>;

export const placedOrderSchema = z.object({
  order: orderSchema,
  payment: paymentInstructionSchema,
});

export type PlacedOrder = z.infer<typeof placedOrderSchema>;

export const orderListSchema = z.object({
  content: z.array(orderSummarySchema),
  page: pageMetaSchema,
});

// ------------------------------------------------------------------ wishlist --

/**
 * A saved item.
 *
 * Carries a full product card because that is what the page draws, plus live availability — for a
 * wishlist, "sold out since you saved it" is the normal case, not the edge case, and the move-to-cart
 * button has to reflect it.
 */
export const wishlistItemSchema = z.object({
  id: z.string().uuid(),
  variantId: z.string().uuid().nullable().optional(),
  variantLabel: z.string().nullable().optional(),
  availableQuantity: z.number().int(),
  inStock: z.boolean(),
  savedAt: instantSchema,
  product: productCardSchema,
});

export type WishlistItem = z.infer<typeof wishlistItemSchema>;

export const wishlistSchema = z.object({
  id: z.string().uuid(),
  name: z.string(),
  isPublic: z.boolean(),
  /** Only ever populated for the list's owner. */
  shareToken: z.string().nullable().optional(),
  itemCount: z.number().int(),
  items: z.array(wishlistItemSchema),
});

export type Wishlist = z.infer<typeof wishlistSchema>;

/** Moving an item changes both badges, so the server returns both states. */
export const wishlistMoveResultSchema = z.object({
  wishlist: wishlistSchema,
  cart: cartSchema,
});

export type WishlistMoveResult = z.infer<typeof wishlistMoveResultSchema>;
