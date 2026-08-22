import { NextResponse } from 'next/server';

/**
 * Liveness probe.
 *
 * Deliberately checks nothing but the process. A liveness probe that calls the API would restart
 * the storefront every time the backend hiccups - which is exactly when you want the storefront up,
 * serving cached pages and a useful error rather than cycling.
 */
export function GET() {
  return NextResponse.json({ status: 'ok' }, { status: 200 });
}

export const dynamic = 'force-dynamic';
