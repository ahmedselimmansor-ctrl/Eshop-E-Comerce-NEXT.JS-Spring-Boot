import { getTranslations } from 'next-intl/server';
import { CreditCard, PackageCheck, RotateCcw, ShieldCheck } from 'lucide-react';
import { Link } from '@/i18n/routing';

export async function SiteFooter({ locale }: { locale: string }) {
  const t = await getTranslations('common');
  const nav = await getTranslations('nav');
  const home = await getTranslations('home');

  const assurances = [
    { icon: PackageCheck, title: home('freeShipping'), hint: home('freeShippingHint', { amount: '1,000 ج.م' }) },
    { icon: CreditCard, title: home('codAvailable'), hint: home('codHint') },
    { icon: RotateCcw, title: home('easyReturns'), hint: home('easyReturnsHint', { days: 14 }) },
    { icon: ShieldCheck, title: home('securePayment'), hint: home('securePaymentHint') },
  ];

  return (
    <footer className="mt-16 border-t border-[var(--border)] bg-[var(--muted)]">
      {/*
       * The assurance strip sits at the top of the footer rather than only on the home page: these
       * are the questions a hesitant shopper has at the moment they are deciding, and they scroll
       * to the bottom to look for them.
       */}
      <div className="border-b border-[var(--border)]">
        <div className="mx-auto grid max-w-7xl gap-6 px-4 py-8 sm:grid-cols-2 lg:grid-cols-4">
          {assurances.map(({ icon: Icon, title, hint }) => (
            <div key={title} className="flex items-start gap-3">
              <Icon className="mt-0.5 size-5 shrink-0 text-brand-600" aria-hidden />
              <div>
                <p className="text-sm font-medium">{title}</p>
                <p className="text-xs text-[var(--muted-foreground)]">{hint}</p>
              </div>
            </div>
          ))}
        </div>
      </div>

      <div className="mx-auto grid max-w-7xl gap-8 px-4 py-10 sm:grid-cols-2 lg:grid-cols-4">
        <div>
          <p className="text-lg font-bold text-brand-700">{t('storeName')}</p>
          <p className="mt-2 text-sm text-[var(--muted-foreground)]">{t('tagline')}</p>
        </div>

        <nav aria-label={nav('categories')}>
          <h2 className="text-sm font-semibold">{nav('categories')}</h2>
          <ul className="mt-3 space-y-2 text-sm text-[var(--muted-foreground)]">
            <li>
              <Link href="/products" className="hover:text-brand-700">
                {nav('deals')}
              </Link>
            </li>
            <li>
              <Link href="/products" className="hover:text-brand-700">
                {nav('newArrivals')}
              </Link>
            </li>
            <li>
              <Link href="/products" className="hover:text-brand-700">
                {nav('bestSellers')}
              </Link>
            </li>
          </ul>
        </nav>

        <nav aria-label={nav('support')}>
          <h2 className="text-sm font-semibold">{nav('support')}</h2>
          <ul className="mt-3 space-y-2 text-sm text-[var(--muted-foreground)]">
            <li>
              <Link href="/orders/track" className="hover:text-brand-700">
                {nav('trackOrder')}
              </Link>
            </li>
            <li>
              <Link href="/account/orders" className="hover:text-brand-700">
                {t('orders')}
              </Link>
            </li>
          </ul>
        </nav>

        <div>
          <h2 className="text-sm font-semibold">
            {locale === 'ar' ? 'وسائل الدفع' : 'Payment methods'}
          </h2>
          <ul className="mt-3 flex flex-wrap gap-2 text-xs text-[var(--muted-foreground)]">
            {['Visa', 'Mastercard', 'Meeza', 'Fawry', locale === 'ar' ? 'الدفع عند الاستلام' : 'Cash on delivery'].map(
              (method) => (
                <li
                  key={method}
                  className="rounded border border-[var(--border)] bg-[var(--background)] px-2 py-1"
                >
                  {method}
                </li>
              ),
            )}
          </ul>
        </div>
      </div>

      <div className="border-t border-[var(--border)]">
        <p className="mx-auto max-w-7xl px-4 py-5 text-center text-xs text-[var(--muted-foreground)]">
          <span className="numeric">© {new Date().getFullYear()}</span> {t('storeName')}
        </p>
      </div>
    </footer>
  );
}
