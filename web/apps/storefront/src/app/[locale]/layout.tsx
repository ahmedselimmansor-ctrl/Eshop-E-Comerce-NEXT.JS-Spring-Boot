import type { Metadata, Viewport } from 'next';
import { NextIntlClientProvider } from 'next-intl';
import { getMessages, getTranslations, setRequestLocale } from 'next-intl/server';
import { notFound } from 'next/navigation';
import type { ReactNode } from 'react';
import { routing, directionOf, isSupportedLocale } from '@/i18n/routing';
import { Providers } from '@/app/providers';
import { SiteHeader } from '@/components/layout/site-header';
import { SiteFooter } from '@/components/layout/site-footer';
import '@/app/globals.css';

/**
 * Both locales are known at build time, so their shells prerender rather than being generated on
 * first request.
 */
export function generateStaticParams() {
  return routing.locales.map((locale) => ({ locale }));
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ locale: string }>;
}): Promise<Metadata> {
  const { locale } = await params;
  const t = await getTranslations({ locale, namespace: 'common' });

  const siteUrl = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://eshop.eg';

  return {
    metadataBase: new URL(siteUrl),
    title: {
      default: `${t('storeName')} — ${t('tagline')}`,
      template: `%s | ${t('storeName')}`,
    },
    description: t('tagline'),

    // Tells search engines the two language versions are the same page, so they are ranked
    // together rather than competing as duplicates.
    alternates: {
      canonical: `/${locale}`,
      languages: {
        ar: '/ar',
        en: '/en',
        'x-default': '/ar',
      },
    },

    openGraph: {
      type: 'website',
      locale: locale === 'ar' ? 'ar_EG' : 'en_US',
      alternateLocale: locale === 'ar' ? 'en_US' : 'ar_EG',
      siteName: t('storeName'),
      url: `${siteUrl}/${locale}`,
    },

    robots: {
      index: true,
      follow: true,
      googleBot: { index: true, follow: true, 'max-image-preview': 'large' },
    },

    icons: { icon: '/favicon.ico', apple: '/apple-touch-icon.png' },
    manifest: '/manifest.webmanifest',
  };
}

export const viewport: Viewport = {
  width: 'device-width',
  initialScale: 1,
  // Zoom is left enabled: disabling it is an accessibility failure, and the layout is responsive
  // enough not to need it.
  maximumScale: 5,
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#ffffff' },
    { media: '(prefers-color-scheme: dark)', color: '#1a1a1a' },
  ],
};

export default async function LocaleLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ locale: string }>;
}) {
  const { locale } = await params;

  if (!isSupportedLocale(locale)) {
    notFound();
  }

  // Required for the static rendering of nested server components to see the right locale.
  setRequestLocale(locale);

  /*
   * Messages are passed explicitly rather than left to be inherited.
   *
   * Client components that call `useTranslations` are rendered on the server during prerendering,
   * where there is no request context to inherit from — without this they throw MISSING_MESSAGE at
   * build time, which is exactly when it is least obvious what went wrong.
   */
  const messages = await getMessages();
  const direction = directionOf(locale);

  return (
    /*
     * `dir` is set on <html>, not on a wrapper.
     *
     * That makes every logical CSS property (ms-*, pe-*, start-*, end-*) resolve correctly
     * throughout the tree, and it is what browsers and assistive technology read to decide text
     * direction, caret movement, and reading order. Setting it lower down leaves the scrollbar and
     * any portal content — dialogs, toasts, dropdowns rendered outside the wrapper — pointing the
     * wrong way.
     */
    <html lang={locale} dir={direction} suppressHydrationWarning>
      <body className="flex min-h-dvh flex-col antialiased">
        <NextIntlClientProvider locale={locale} messages={messages}>
          <Providers>
            {/* First stop for keyboard users, before the whole navigation. */}
            <a
              href="#main"
              className="sr-only-focusable absolute top-2 start-2 z-50 rounded-md bg-[var(--primary)] px-4 py-2 text-sm text-[var(--primary-foreground)]"
            >
              {locale === 'ar' ? 'تخطَّ إلى المحتوى' : 'Skip to content'}
            </a>

            <SiteHeader locale={locale} />

            <main id="main" className="flex-1">
              {children}
            </main>

            <SiteFooter locale={locale} />
          </Providers>
        </NextIntlClientProvider>
      </body>
    </html>
  );
}
