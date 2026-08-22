'use client';

import type { OrderStatus, PaymentStatus } from '@eshop/api-client';
import { Badge } from '@/components/ui/primitives';

/*
 * Status colour is assigned by outcome, not by taste.
 *
 * Green means settled, red means the store lost the sale or the money, amber means someone has to
 * act, blue means in flight. An operator scanning two hundred rows should be able to find the ones
 * that need attention without reading a single word.
 */

type Tone = 'neutral' | 'brand' | 'success' | 'warning' | 'danger';

const ORDER_TONES: Record<OrderStatus, Tone> = {
  PENDING_PAYMENT: 'warning',
  PAID: 'brand',
  PROCESSING: 'brand',
  SHIPPED: 'brand',
  DELIVERED: 'success',
  CANCELLED: 'danger',
  REFUNDED: 'neutral',
  PARTIALLY_REFUNDED: 'neutral',
  FAILED: 'danger',
};

const PAYMENT_TONES: Record<PaymentStatus, Tone> = {
  PENDING: 'warning',
  AUTHORIZED: 'brand',
  PAID: 'success',
  FAILED: 'danger',
  REFUNDED: 'neutral',
  PARTIALLY_REFUNDED: 'neutral',
};

const REVIEW_TONES: Record<string, Tone> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
};

const USER_TONES: Record<string, Tone> = {
  ACTIVE: 'success',
  SUSPENDED: 'warning',
  DELETED: 'danger',
};

const PRODUCT_TONES: Record<string, Tone> = {
  ACTIVE: 'success',
  DRAFT: 'neutral',
  ARCHIVED: 'warning',
};

/** SHIPPED → "Shipped", PENDING_PAYMENT → "Pending payment". */
export function humanise(status: string): string {
  const words = status.toLowerCase().split('_').filter(Boolean);
  const [first, ...rest] = words;
  if (!first) return status;
  return [first.charAt(0).toUpperCase() + first.slice(1), ...rest].join(' ');
}

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  return <Badge tone={ORDER_TONES[status] ?? 'neutral'}>{humanise(status)}</Badge>;
}

export function PaymentStatusBadge({ status }: { status: PaymentStatus }) {
  return <Badge tone={PAYMENT_TONES[status] ?? 'neutral'}>{humanise(status)}</Badge>;
}

export function ReviewStatusBadge({ status }: { status: string }) {
  return <Badge tone={REVIEW_TONES[status] ?? 'neutral'}>{humanise(status)}</Badge>;
}

export function UserStatusBadge({ status }: { status: string }) {
  return <Badge tone={USER_TONES[status] ?? 'neutral'}>{humanise(status)}</Badge>;
}

export function ProductStatusBadge({ status }: { status: string }) {
  return <Badge tone={PRODUCT_TONES[status] ?? 'neutral'}>{humanise(status)}</Badge>;
}
