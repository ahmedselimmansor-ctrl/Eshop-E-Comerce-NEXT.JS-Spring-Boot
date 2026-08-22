import { z } from 'zod';

/**
 * Money as the API sends it: an amount **string** plus a currency.
 *
 * The amount is a string, not a number, on purpose. `1299.99` survives a JSON round trip fine, but
 * once JavaScript adds a few of them together you get `3899.9700000000003` on an invoice. Keeping
 * the transport representation exact means arithmetic is a deliberate act (see `moneyToNumber`)
 * rather than something that quietly happens with the wrong type.
 */
export const moneySchema = z.object({
  amount: z.string(),
  currency: z.string().length(3),
});

export type Money = z.infer<typeof moneySchema>;

/** Parses a money amount for arithmetic. Use only where a small float error is acceptable. */
export function moneyToNumber(money: Money | null | undefined): number {
  return money ? Number.parseFloat(money.amount) : 0;
}

/**
 * Formats money for display in the caller's locale.
 *
 * Arabic uses Western digits here (`ar-EG-u-nu-latn`) rather than Eastern Arabic numerals: EGP
 * prices are overwhelmingly written with Western digits in Egyptian commerce, and mixing the two
 * across a page reads as a bug.
 */
export function formatMoney(money: Money | null | undefined, locale: string): string {
  if (!money) return '';

  const formatter = new Intl.NumberFormat(
    locale === 'ar' ? 'ar-EG-u-nu-latn' : 'en-EG',
    {
      style: 'currency',
      currency: money.currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    },
  );
  return formatter.format(moneyToNumber(money));
}

/** The pagination envelope every list endpoint returns. */
export const pageMetaSchema = z.object({
  number: z.number().int(),
  size: z.number().int(),
  totalElements: z.number().int(),
  totalPages: z.number().int(),
  first: z.boolean(),
  last: z.boolean(),
});

export type PageMeta = z.infer<typeof pageMetaSchema>;

export function pageSchema<T extends z.ZodTypeAny>(item: T) {
  return z.object({
    content: z.array(item),
    page: pageMetaSchema,
  });
}

/**
 * The stable error vocabulary from the API.
 *
 * Client code branches on `code`, never on `detail` — `detail` is human prose that is reworded and
 * translated freely, and matching on it is a bug waiting for the next copy change.
 */
export const errorCodeSchema = z.enum([
  'VALIDATION_ERROR',
  'MALFORMED_REQUEST',
  'UNSUPPORTED_MEDIA_TYPE',
  'PAYLOAD_TOO_LARGE',
  'UNAUTHENTICATED',
  'INVALID_CREDENTIALS',
  'ACCOUNT_LOCKED',
  'ACCOUNT_SUSPENDED',
  'EMAIL_NOT_VERIFIED',
  'TOKEN_EXPIRED',
  'TOKEN_INVALID',
  'TOKEN_REUSE_DETECTED',
  'FORBIDDEN',
  'NOT_FOUND',
  'CONFLICT',
  'DUPLICATE_RESOURCE',
  'EMAIL_ALREADY_REGISTERED',
  'PHONE_ALREADY_REGISTERED',
  'CART_EMPTY',
  'CART_LIMIT_EXCEEDED',
  'OUT_OF_STOCK',
  'PRODUCT_UNAVAILABLE',
  'PRICE_CHANGED',
  'COUPON_INVALID',
  'COUPON_EXPIRED',
  'COUPON_NOT_STARTED',
  'COUPON_USAGE_EXCEEDED',
  'COUPON_MIN_ORDER',
  'COUPON_NOT_APPLICABLE',
  'ORDER_NOT_CANCELLABLE',
  'ORDER_ALREADY_PAID',
  'INVALID_STATUS_TRANSITION',
  'SHIPPING_UNAVAILABLE',
  'COD_NOT_SUPPORTED',
  'PAYMENT_FAILED',
  'PAYMENT_ALREADY_PROCESSED',
  'PAYMENT_PROVIDER_ERROR',
  'REFUND_EXCEEDS_PAYMENT',
  'RATE_LIMITED',
  'IDEMPOTENCY_CONFLICT',
  'IDEMPOTENCY_IN_PROGRESS',
  'SEARCH_UNAVAILABLE',
  'STORAGE_ERROR',
  'SERVICE_UNAVAILABLE',
  'INTERNAL_ERROR',
]);

export type ErrorCode = z.infer<typeof errorCodeSchema>;

/** A single field-level problem, for inline form errors. */
export const fieldViolationSchema = z.object({
  field: z.string(),
  code: z.string().nullable(),
  message: z.string().nullable(),
  rejectedValue: z.unknown().nullable(),
});

export type FieldViolation = z.infer<typeof fieldViolationSchema>;

/** RFC 9457 problem document — the shape of every error the API returns. */
export const problemDetailSchema = z.object({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().int(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  code: z.string().optional(),
  requestId: z.string().optional(),
  timestamp: z.string().optional(),
  errors: z.array(fieldViolationSchema).optional(),
  retryAfterSeconds: z.number().optional(),
  availableQuantity: z.number().optional(),
});

export type ProblemDetail = z.infer<typeof problemDetailSchema>;

export const localeSchema = z.enum(['ar', 'en']);
export type Locale = z.infer<typeof localeSchema>;

/** ISO-8601 instant, as the API always sends it. */
export const instantSchema = z.string().datetime({ offset: true }).or(z.string());
