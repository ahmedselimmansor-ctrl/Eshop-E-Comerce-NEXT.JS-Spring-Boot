'use client';

import { Languages } from 'lucide-react';
import { useParams } from 'next/navigation';
import { useTransition } from 'react';
import { usePathname, useRouter } from '@/i18n/routing';

/**
 * Switches between Arabic and English.
 *
 * Navigates to the *same page* in the other locale rather than sending the visitor to the home
 * page - the equivalent of a "translate this page" button, which is what someone reading a product
 * page actually wants.
 */
export function LocaleSwitcher({ currentLocale }: { currentLocale: string }) {
  const router = useRouter();
  const pathname = usePathname();
  const params = useParams();
  const [isPending, startTransition] = useTransition();

  const target = currentLocale === 'ar' ? 'en' : 'ar';

  function switchLocale() {
    startTransition(() => {
      // `pathname` here is the *unlocalised* route pattern, and `params` carries its segments, so
      // next-intl can rebuild the localised URL for the other language - including the translated
      // path segments (`/ar/منتجات/[slug]` ↔ `/en/products/[slug]`).
      router.replace(
        // @ts-expect-error - params are correct for the current route by construction, but the
        // pathname is only known at runtime so the generic cannot be narrowed here.
        { pathname, params },
        { locale: target },
      );
    });
  }

  return (
    <button
      type="button"
      onClick={switchLocale}
      disabled={isPending}
      className="inline-flex h-10 items-center gap-1.5 rounded-[var(--radius-control)] px-3 text-sm hover:bg-[var(--muted)] disabled:opacity-50"
      // Announced in the language being switched *to*, so a screen reader pronounces it correctly.
      lang={target}
      aria-label={target === 'ar' ? 'التبديل إلى العربية' : 'Switch to English'}
    >
      <Languages className="size-4" aria-hidden />
      <span className="hidden sm:inline">{target === 'ar' ? 'العربية' : 'English'}</span>
    </button>
  );
}
