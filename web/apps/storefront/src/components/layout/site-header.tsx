import { getTranslations } from 'next-intl/server';
import { Menu, Search } from 'lucide-react';
import { Link } from '@/i18n/routing';
import { serverApi } from '@/lib/api-server';
import { LocaleSwitcher } from './locale-switcher';
import { CartButton } from './cart-button';
import { AccountMenu } from './account-menu';
import { SearchBox } from './search-box';
import type { Category } from '@eshop/api-client';

/**
 * The site header.
 *
 * A server component that fetches the navigation once per render and passes it down. The
 * interactive pieces - search suggestions, the cart badge, the account menu - are separate client
 * islands, so the header itself ships no JavaScript.
 */
export async function SiteHeader({ locale }: { locale: string }) {
  const t = await getTranslations('common');
  const nav = await getTranslations('nav');

  // The category tree is revalidated on a timer inside the API client, so this is a cache read on
  // almost every request rather than a live call.
  let categories: Category[] = [];
  try {
    categories = await (await serverApi()).catalog.categoryTree();
  } catch {
    // A header without its menu is far better than a 500 on every page of the site.
    categories = [];
  }

  return (
    <header className="sticky top-0 z-40 border-b border-[var(--border)] bg-[var(--background)]/95 backdrop-blur supports-[backdrop-filter]:bg-[var(--background)]/80">
      <div className="mx-auto flex h-[var(--spacing-header)] max-w-7xl items-center gap-3 px-4">
        <button
          type="button"
          className="inline-flex size-10 items-center justify-center rounded-[var(--radius-control)] hover:bg-[var(--muted)] lg:hidden"
          aria-label={nav('openMenu')}
        >
          <Menu className="size-5" aria-hidden />
        </button>

        <Link href="/" className="shrink-0 text-lg font-bold text-brand-700">
          {t('storeName')}
        </Link>

        {/* Hidden on phones, where the dedicated search row below takes over. */}
        <div className="hidden flex-1 md:block">
          <SearchBox placeholder={t('search')} />
        </div>

        <div className="ms-auto flex items-center gap-1">
          <Link
            href="/search"
            className="inline-flex size-10 items-center justify-center rounded-[var(--radius-control)] hover:bg-[var(--muted)] md:hidden"
            aria-label={t('searchShort')}
          >
            <Search className="size-5" aria-hidden />
          </Link>

          <LocaleSwitcher currentLocale={locale} />
          <AccountMenu />
          <CartButton />
        </div>
      </div>

      {/* Primary categories, desktop only. */}
      <nav aria-label={nav('categories')} className="hidden border-t border-[var(--border)] lg:block">
        <div className="mx-auto flex max-w-7xl items-center gap-1 overflow-x-auto px-4">
          {categories.slice(0, 8).map((category) => (
            <Link
              key={category.id}
              href={{ pathname: '/categories/[slug]', params: { slug: category.slug } }}
              className="whitespace-nowrap px-3 py-2.5 text-sm hover:text-brand-700"
            >
              {category.name}
            </Link>
          ))}

          <Link
            href="/products"
            className="ms-auto whitespace-nowrap px-3 py-2.5 text-sm font-medium text-sale-600"
          >
            {nav('deals')}
          </Link>
        </div>
      </nav>
    </header>
  );
}
