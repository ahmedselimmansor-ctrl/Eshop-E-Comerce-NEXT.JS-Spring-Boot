import { cookies, headers } from 'next/headers';
import { createApi, type EshopApi } from '@eshop/api-client';
import { isSupportedLocale, routing } from '@/i18n/routing';

/**
 * The API client for server components and route handlers.
 *
 * Two things differ from the browser client:
 *
 * 1. It calls the **cluster-internal** address, skipping the external load balancer and Cloud Armor
 *    entirely. A server component rendering a product page has no reason to leave the cluster, and
 *    the round trip through the edge would add latency to every server render.
 *
 * 2. It reads the locale and the guest session from the incoming request rather than from module
 *    state. Server components render concurrently for different visitors in the same process, so
 *    anything stored at module scope would leak one visitor's context into another's page - the
 *    kind of bug that only appears under load.
 */
export async function serverApi(): Promise<EshopApi> {
  const [cookieStore, headerStore] = await Promise.all([cookies(), headers()]);

  const pathname = headerStore.get('x-pathname') ?? '';
  const localeFromPath = pathname.split('/')[1];

  const locale: string = isSupportedLocale(localeFromPath)
    ? localeFromPath
    : (cookieStore.get('NEXT_LOCALE')?.value ?? routing.defaultLocale);

  return createApi({
    baseUrl: process.env.API_INTERNAL_BASE_URL || process.env.NEXT_PUBLIC_API_BASE_URL || '',
    getLocale: () => locale,
    getSessionId: () => cookieStore.get('eshop_session')?.value,

    // Server-side rendering has no access token - the access token lives in browser memory. Pages
    // that need per-customer data fetch it from the browser instead, which is also what keeps them
    // cacheable at the edge.
    getAccessToken: () => null,
    credentials: 'omit',

    defaultHeaders: {
      // Propagates the trace across the browser → Next.js → API hop, so one request id follows the
      // whole chain in Cloud Trace.
      ...(headerStore.get('x-request-id')
        ? { 'X-Request-Id': headerStore.get('x-request-id') as string }
        : {}),
    },
  });
}

/**
 * Resolves the active locale in a server component.
 *
 * Reads the path first because that is authoritative - the URL is what was requested, and the
 * cookie is only a fallback for a request that somehow arrived without a locale prefix.
 */
export async function currentLocale(): Promise<string> {
  const headerStore = await headers();
  const pathname = headerStore.get('x-pathname') ?? '';
  const fromPath = pathname.split('/')[1];

  if (isSupportedLocale(fromPath)) {
    return fromPath;
  }

  const cookieStore = await cookies();
  return cookieStore.get('NEXT_LOCALE')?.value ?? routing.defaultLocale;
}
