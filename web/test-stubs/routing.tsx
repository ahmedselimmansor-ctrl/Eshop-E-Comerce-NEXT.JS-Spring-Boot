import type { ReactNode } from 'react';

/**
 * A stand-in for next-intl's locale-aware `Link`.
 *
 * The real one resolves a typed `{ pathname, params }` href against the routing table and prefixes
 * the locale. None of that is what a component test is checking, and pulling in the router would
 * make these tests about next-intl rather than about the component.
 */
export function Link({
  href,
  children,
  ...rest
}: {
  href: string | { pathname: string; params?: Record<string, string> };
  children: ReactNode;
  [key: string]: unknown;
}) {
  const resolved =
    typeof href === 'string'
      ? href
      : Object.entries(href.params ?? {}).reduce(
          (path, [key, value]) => path.replace(`[${key}]`, value),
          href.pathname,
        );

  return (
    <a href={resolved} {...(rest as Record<string, unknown>)}>
      {children}
    </a>
  );
}

export const routing = { locales: ['ar', 'en'], defaultLocale: 'ar' };
export const redirect = () => undefined;
export const usePathname = () => '/';
export const useRouter = () => ({ push: () => undefined, replace: () => undefined });
