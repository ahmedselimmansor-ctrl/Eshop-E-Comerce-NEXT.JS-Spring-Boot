import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Merges class names, resolving Tailwind conflicts.
 *
 * `clsx` handles the conditionals; `twMerge` makes the last conflicting utility win, so a variant's
 * `px-4` can be overridden by a caller's `px-8` instead of both landing in the class list and the
 * outcome depending on stylesheet order.
 */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * Builds a product image URL sized for the slot it will occupy.
 *
 * Cloud CDN serves the object; the size hint lets the image pipeline return an appropriately scaled
 * variant rather than a full-resolution original scaled down in the browser.
 */
export function imageUrl(url: string | null | undefined, width?: number): string {
  if (!url) return '/placeholder-product.svg';
  if (!width) return url;

  const separator = url.includes('?') ? '&' : '?';
  return `${url}${separator}w=${width}`;
}

/** Formats a percentage discount for a badge. */
export function formatDiscount(percent: number, locale: string): string {
  return locale === 'ar' ? `خصم ${percent}%` : `${percent}% off`;
}

/**
 * Relative time, for order timelines and review dates.
 *
 * Falls back to an absolute date beyond a week: "23 days ago" is harder to place than a date, and
 * on an order history the date is what a customer actually needs.
 */
export function formatRelativeTime(iso: string, locale: string): string {
  const then = new Date(iso);
  const seconds = Math.round((then.getTime() - Date.now()) / 1000);
  const absolute = Math.abs(seconds);

  if (absolute > 60 * 60 * 24 * 7) {
    return new Intl.DateTimeFormat(locale === 'ar' ? 'ar-EG-u-nu-latn' : 'en-GB', {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
    }).format(then);
  }

  const formatter = new Intl.RelativeTimeFormat(locale === 'ar' ? 'ar-EG' : 'en', {
    numeric: 'auto',
  });

  const divisions: [number, Intl.RelativeTimeFormatUnit][] = [
    [60, 'second'],
    [60, 'minute'],
    [24, 'hour'],
    [7, 'day'],
  ];

  let value = seconds;
  for (const [amount, unit] of divisions) {
    if (Math.abs(value) < amount) {
      return formatter.format(Math.round(value), unit);
    }
    value /= amount;
  }
  return formatter.format(Math.round(value), 'week');
}

/**
 * Stable guest cart identifier.
 *
 * Kept in `localStorage` rather than a cookie so it is never sent on requests that do not need it,
 * and so it survives the visitor clearing cookies for tracking reasons - losing a cart to a privacy
 * setting is a bad trade for everyone.
 */
const SESSION_KEY = 'eshop.sessionId';

export function getOrCreateSessionId(): string {
  if (typeof window === 'undefined') return '';

  let sessionId = window.localStorage.getItem(SESSION_KEY);
  if (!sessionId) {
    sessionId = crypto.randomUUID();
    window.localStorage.setItem(SESSION_KEY, sessionId);
  }
  return sessionId;
}

export function clearSessionId(): void {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(SESSION_KEY);
  }
}

/** Debounces a function - used for the search-as-you-type box and filter sliders. */
export function debounce<Args extends unknown[]>(
  fn: (...args: Args) => void,
  delayMs: number,
): (...args: Args) => void {
  let timer: ReturnType<typeof setTimeout> | undefined;

  return (...args: Args) => {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => fn(...args), delayMs);
  };
}

/** Truncates on a word boundary, for card descriptions and meta tags. */
export function truncate(text: string | null | undefined, maxLength: number): string {
  if (!text || text.length <= maxLength) return text ?? '';

  const cut = text.slice(0, maxLength);
  const lastSpace = cut.lastIndexOf(' ');
  return (lastSpace > maxLength / 2 ? cut.slice(0, lastSpace) : cut) + '…';
}

/** Egypt's governorates, in the exact spelling the API matches shipping zones against. */
export const EGYPT_GOVERNORATES = [
  'القاهرة',
  'الجيزة',
  'القليوبية',
  'الإسكندرية',
  'البحيرة',
  'كفر الشيخ',
  'الغربية',
  'المنوفية',
  'الدقهلية',
  'دمياط',
  'الشرقية',
  'بورسعيد',
  'الإسماعيلية',
  'السويس',
  'شمال سيناء',
  'جنوب سيناء',
  'الفيوم',
  'بني سويف',
  'المنيا',
  'أسيوط',
  'سوهاج',
  'قنا',
  'الأقصر',
  'أسوان',
  'البحر الأحمر',
  'الوادي الجديد',
  'مطروح',
] as const;
