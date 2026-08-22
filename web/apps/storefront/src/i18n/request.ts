import { getRequestConfig } from 'next-intl/server';
import { isSupportedLocale, routing } from './routing';

/**
 * Per-request i18n configuration.
 *
 * Also the single place where date, number, and currency formats are defined - leaving those to
 * each call site produces a page where the same price appears three different ways.
 */
export default getRequestConfig(async ({ requestLocale }) => {
  const requested = await requestLocale;
  const locale = isSupportedLocale(requested) ? requested : routing.defaultLocale;

  return {
    locale,
    messages: (await import(`../messages/${locale}.json`)).default,

    // Cairo, always. The store, its couriers, and its customers are all here, so an order placed
    // at 11pm should not show as "tomorrow" because the server happens to run in UTC.
    timeZone: 'Africa/Cairo',

    formats: {
      dateTime: {
        short: { day: 'numeric', month: 'short', year: 'numeric' },
        long: {
          day: 'numeric',
          month: 'long',
          year: 'numeric',
          hour: 'numeric',
          minute: 'numeric',
        },
      },
      number: {
        // Western digits in Arabic too: EGP prices are written that way in Egyptian commerce, and
        // mixing numeral systems across a page reads as a rendering bug.
        currency: {
          style: 'currency',
          currency: 'EGP',
          minimumFractionDigits: 2,
          maximumFractionDigits: 2,
          numberingSystem: 'latn',
        },
        integer: { maximumFractionDigits: 0, numberingSystem: 'latn' },
      },
    },
  };
});
