import { defineRouting } from 'next-intl/routing';
import { createNavigation } from 'next-intl/navigation';

/**
 * Locale routing.
 *
 * Arabic is the default and is **not** hidden from the URL (`localePrefix: 'always'`). An
 * unprefixed default locale looks tidier but makes `/products/iphone-15` ambiguous - is it Arabic,
 * or a language-neutral canonical? Being explicit means every page has exactly one URL per
 * language, which is what `hreflang`, sitemaps, and shared links all depend on.
 */
export const routing = defineRouting({
  locales: ['ar', 'en'],
  defaultLocale: 'ar',
  localePrefix: 'always',

  // The cookie records a returning visitor's choice; a first visit falls back to Accept-Language.
  localeCookie: {
    name: 'NEXT_LOCALE',
    maxAge: 60 * 60 * 24 * 365,
    sameSite: 'lax',
  },

  /**
   * Localised path segments.
   *
   * `/ar/سلة` reads as a real Arabic URL rather than a transliteration, and it is what an Arabic
   * speaker recognises when a link is shared in a message. Segments not listed here stay identical
   * across locales.
   */
  pathnames: {
    '/': '/',
    '/products': { ar: '/منتجات', en: '/products' },
    '/products/[slug]': { ar: '/منتجات/[slug]', en: '/products/[slug]' },
    '/categories/[slug]': { ar: '/اقسام/[slug]', en: '/categories/[slug]' },
    '/brands/[slug]': { ar: '/ماركات/[slug]', en: '/brands/[slug]' },
    '/search': { ar: '/بحث', en: '/search' },
    '/cart': { ar: '/سلة', en: '/cart' },
    '/checkout': { ar: '/الدفع', en: '/checkout' },
    '/checkout/callback': { ar: '/الدفع/تأكيد', en: '/checkout/callback' },
    '/account': { ar: '/حسابي', en: '/account' },
    '/account/orders': { ar: '/حسابي/طلباتي', en: '/account/orders' },
    '/account/orders/[orderNumber]': {
      ar: '/حسابي/طلباتي/[orderNumber]',
      en: '/account/orders/[orderNumber]',
    },
    '/account/addresses': { ar: '/حسابي/عناويني', en: '/account/addresses' },
    '/account/wishlist': { ar: '/حسابي/المفضلة', en: '/account/wishlist' },
    // Someone else's shared list. Outside /account because the viewer is usually not signed in.
    '/wishlist/[token]': { ar: '/مفضلة/[token]', en: '/wishlist/[token]' },
    '/auth/login': { ar: '/دخول', en: '/auth/login' },
    '/auth/register': { ar: '/تسجيل', en: '/auth/register' },
    '/auth/forgot-password': { ar: '/نسيت-كلمة-المرور', en: '/auth/forgot-password' },
    '/auth/reset-password': { ar: '/تعيين-كلمة-المرور', en: '/auth/reset-password' },
    '/auth/verify-email': { ar: '/تفعيل-البريد', en: '/auth/verify-email' },
    '/auth/callback': '/auth/callback',
    '/orders/track': { ar: '/تتبع-الطلب', en: '/orders/track' },
  },
});

export type Locale = (typeof routing.locales)[number];
export type AppPathname = keyof typeof routing.pathnames;

/**
 * Locale-aware navigation primitives.
 *
 * Always import `Link`, `redirect`, `useRouter`, and `usePathname` from here rather than from
 * `next/link` or `next/navigation`. These variants carry the active locale automatically; the
 * built-ins would drop it and bounce the visitor back to Arabic mid-session.
 */
export const { Link, redirect, usePathname, useRouter, getPathname } = createNavigation(routing);

/** Text direction for a locale. Arabic is the only right-to-left language here today. */
export function directionOf(locale: string): 'rtl' | 'ltr' {
  return locale === 'ar' ? 'rtl' : 'ltr';
}

/**
 * Type guard for a locale from an untrusted source (a URL segment, a cookie).
 *
 * Written here rather than imported: `hasLocale` is not exported by this version of next-intl, and
 * a local guard also narrows to the `Locale` union, which the layout needs.
 */
export function isSupportedLocale(value: string | undefined): value is Locale {
  return value !== undefined && (routing.locales as readonly string[]).includes(value);
}
