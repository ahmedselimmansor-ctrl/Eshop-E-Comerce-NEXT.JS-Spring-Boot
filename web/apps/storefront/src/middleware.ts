import createMiddleware from 'next-intl/middleware';
import { NextRequest, NextResponse } from 'next/server';
import { routing } from './i18n/routing';

const intlMiddleware = createMiddleware(routing);

/** Paths that require a signed-in customer, checked after the locale prefix is stripped. */
const PROTECTED_PREFIXES = ['/account'];

/** Signed-in visitors have no business on the sign-in or registration screens. */
const GUEST_ONLY_PREFIXES = ['/auth/login', '/auth/register'];

export default function middleware(request: NextRequest) {
  const response = intlMiddleware(request);

  const { pathname } = request.nextUrl;
  const locale = pathname.split('/')[1] ?? routing.defaultLocale;
  const withoutLocale = pathname.replace(`/${locale}`, '') || '/';

  // Presence of the refresh cookie is the only auth signal available here. The access token lives
  // in memory and is not readable from the edge, so this is a routing hint, not an authorisation
  // decision — every protected page still verifies server-side, and every API call is checked by
  // the backend. Treating this cookie as proof of identity would be a real hole.
  const hasSession = request.cookies.has('refresh_token');

  if (!hasSession && PROTECTED_PREFIXES.some((prefix) => withoutLocale.startsWith(prefix))) {
    const login = new URL(`/${locale}/auth/login`, request.url);
    // Preserved so the visitor lands where they were headed after signing in.
    login.searchParams.set('next', pathname);
    return NextResponse.redirect(login);
  }

  if (hasSession && GUEST_ONLY_PREFIXES.some((prefix) => withoutLocale.startsWith(prefix))) {
    return NextResponse.redirect(new URL(`/${locale}`, request.url));
  }

  // Read by server components that need the current path without prop-drilling it down the tree.
  response.headers.set('x-pathname', pathname);
  return response;
}

export const config = {
  /*
   * Everything except Next internals, the BFF route handlers, and static files.
   *
   * `/api` is excluded deliberately: those handlers are same-origin BFF endpoints that must not be
   * locale-prefixed, and running the i18n middleware over them would rewrite their paths.
   */
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico|robots.txt|sitemap.xml|.*\\..*).*)'],
};
