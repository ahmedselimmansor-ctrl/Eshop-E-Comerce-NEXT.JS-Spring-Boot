import { NextRequest, NextResponse } from 'next/server';

/**
 * Same-origin proxy to the backend API.
 *
 * The browser talks to `/api/v1/*` on the storefront's own origin, and this handler forwards to
 * the API. The path is identical to the backend's, so the shared API client needs no separate
 * base URL for the browser — it simply calls the same paths against the current origin. That indirection exists for one reason: the refresh token is an `HttpOnly;
 * SameSite=Strict` cookie, and a strict cookie is not sent on a cross-origin request. Calling the
 * API domain directly from the browser would mean either loosening the cookie to `SameSite=None` —
 * giving up the CSRF protection that strict setting provides — or keeping the refresh token
 * somewhere JavaScript can read it, which is worse.
 *
 * Everything else the proxy does follows from that: forward the cookie in, forward `Set-Cookie`
 * out, and preserve status codes and bodies so the client's error handling is unchanged.
 */

const API_BASE = process.env.API_INTERNAL_BASE_URL || process.env.NEXT_PUBLIC_API_BASE_URL || '';

/**
 * Headers that must not be copied through.
 *
 * `host` would point at the storefront rather than the API; `content-length` and the encoding
 * headers describe a body the fetch layer re-encodes; `connection` is hop-by-hop.
 */
const STRIPPED_REQUEST_HEADERS = new Set([
  'host',
  'connection',
  'content-length',
  'accept-encoding',
  'transfer-encoding',
]);

const STRIPPED_RESPONSE_HEADERS = new Set([
  'content-encoding',
  'content-length',
  'transfer-encoding',
  'connection',
]);

async function proxy(request: NextRequest, path: string[]): Promise<NextResponse> {
  const target = new URL(`${API_BASE}/api/v1/${path.join('/')}`);
  request.nextUrl.searchParams.forEach((value, key) => target.searchParams.append(key, value));

  const headers = new Headers();
  request.headers.forEach((value, key) => {
    if (!STRIPPED_REQUEST_HEADERS.has(key.toLowerCase())) {
      headers.set(key, value);
    }
  });

  // The real client IP, so rate limiting and audit rows attribute the request to the visitor
  // rather than to the storefront pod.
  const forwardedFor = request.headers.get('x-forwarded-for');
  if (forwardedFor) {
    headers.set('X-Forwarded-For', forwardedFor);
  }

  const body = ['GET', 'HEAD'].includes(request.method) ? undefined : await request.text();

  let upstream: Response;
  try {
    upstream = await fetch(target.toString(), {
      method: request.method,
      headers,
      body,
      redirect: 'manual',
      cache: 'no-store',
    });
  } catch {
    // The API is unreachable. Answered in the same problem-document shape as every other error so
    // the client's error handling does not need a special case for "the proxy itself failed".
    return NextResponse.json(
      {
        status: 503,
        code: 'SERVICE_UNAVAILABLE',
        detail: 'تعذر الاتصال بالخادم',
        title: 'Upstream unavailable',
      },
      { status: 503, headers: { 'Content-Type': 'application/problem+json' } },
    );
  }

  const responseHeaders = new Headers();
  upstream.headers.forEach((value, key) => {
    if (!STRIPPED_RESPONSE_HEADERS.has(key.toLowerCase())) {
      responseHeaders.set(key, value);
    }
  });

  // Set-Cookie can appear more than once (an issued refresh cookie plus a cleared one on logout),
  // and Headers#set would collapse them into one. getSetCookie preserves each.
  const setCookies = upstream.headers.getSetCookie?.() ?? [];
  if (setCookies.length > 0) {
    responseHeaders.delete('set-cookie');
    setCookies.forEach((cookie) => responseHeaders.append('set-cookie', cookie));
  }

  return new NextResponse(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: responseHeaders,
  });
}

type RouteContext = { params: Promise<{ path: string[] }> };

export async function GET(request: NextRequest, context: RouteContext) {
  return proxy(request, (await context.params).path);
}

export async function POST(request: NextRequest, context: RouteContext) {
  return proxy(request, (await context.params).path);
}

export async function PUT(request: NextRequest, context: RouteContext) {
  return proxy(request, (await context.params).path);
}

export async function PATCH(request: NextRequest, context: RouteContext) {
  return proxy(request, (await context.params).path);
}

export async function DELETE(request: NextRequest, context: RouteContext) {
  return proxy(request, (await context.params).path);
}

/** Never prerendered or cached — every call is per-visitor and often authenticated. */
export const dynamic = 'force-dynamic';
export const runtime = 'nodejs';
