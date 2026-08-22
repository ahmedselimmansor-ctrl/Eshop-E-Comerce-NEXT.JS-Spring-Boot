import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';
import type { Money } from '@eshop/api-client';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Money, in the console's single locale.
 *
 * The storefront formats per visitor; staff always see `en-EG` so figures line up column-wise in a
 * table and a screenshot pasted into a ticket reads the same for everyone.
 */
export function formatMoney(money: Money | null | undefined): string {
  if (!money) return '—';
  return new Intl.NumberFormat('en-EG', {
    style: 'currency',
    currency: money.currency,
    maximumFractionDigits: 2,
  }).format(Number(money.amount));
}

/** Compact form for chart axes and tiles, where the full figure would not fit. */
export function formatMoneyCompact(money: Money | null | undefined): string {
  if (!money) return '—';
  return new Intl.NumberFormat('en-EG', {
    style: 'currency',
    currency: money.currency,
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(Number(money.amount));
}

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—';
  return new Intl.NumberFormat('en-EG').format(value);
}

/**
 * A percentage change, signed.
 *
 * Returns null when there is nothing to compare against. A tile that renders "+∞%" because the
 * previous period was zero is worse than one that renders nothing.
 */
export function formatChange(percent: number | null | undefined): string | null {
  if (percent === null || percent === undefined || !Number.isFinite(percent)) return null;
  const rounded = Math.round(percent * 10) / 10;
  return `${rounded > 0 ? '+' : ''}${rounded}%`;
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-GB', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(iso));
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium' }).format(new Date(iso));
}

/** "3 minutes ago" — for feeds, where the gap matters more than the wall-clock time. */
export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '—';

  const seconds = (Date.now() - new Date(iso).getTime()) / 1000;
  const formatter = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

  const steps: [Intl.RelativeTimeFormatUnit, number][] = [
    ['second', 60],
    ['minute', 60],
    ['hour', 24],
    ['day', 7],
    ['week', 4.35],
    ['month', 12],
  ];

  let value = seconds;
  for (const [unit, size] of steps) {
    if (Math.abs(value) < size) {
      return formatter.format(-Math.round(value), unit);
    }
    value /= size;
  }
  return formatter.format(-Math.round(value), 'year');
}

/** Reads a decimal string from the API into a number for a form field. */
export function toNumber(value: string | number | null | undefined): number | undefined {
  if (value === null || value === undefined || value === '') return undefined;
  const parsed = typeof value === 'number' ? value : Number.parseFloat(value);
  return Number.isFinite(parsed) ? parsed : undefined;
}
