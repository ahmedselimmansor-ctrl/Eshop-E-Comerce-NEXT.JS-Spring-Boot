import { NextResponse } from 'next/server';

/** Liveness probe — checks the process only, never the API behind it. */
export function GET() {
  return NextResponse.json({ status: 'ok' }, { status: 200 });
}

export const dynamic = 'force-dynamic';
