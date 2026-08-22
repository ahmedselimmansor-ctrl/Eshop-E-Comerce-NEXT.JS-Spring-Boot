import { z } from 'zod';
import { problemDetailSchema, type ErrorCode, type FieldViolation, type ProblemDetail } from './schemas/common';

/**
 * A failed API call, carrying the parsed problem document.
 *
 * Callers branch on {@link code} - the stable machine value - never on the message, which is prose
 * the backend rewords and translates freely.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode | string;
  readonly detail: string;
  readonly violations: FieldViolation[];
  readonly requestId?: string;
  readonly problem: ProblemDetail;

  constructor(problem: ProblemDetail) {
    super(problem.detail ?? problem.title ?? `HTTP ${problem.status}`);
    this.name = 'ApiError';
    this.status = problem.status;
    this.code = problem.code ?? 'INTERNAL_ERROR';
    this.detail = problem.detail ?? '';
    this.violations = problem.errors ?? [];
    this.requestId = problem.requestId;
    this.problem = problem;
  }

  /** Field errors keyed by field name, ready to feed into react-hook-form's `setError`. */
  fieldErrors(): Record<string, string> {
    return Object.fromEntries(
      this.violations
        .filter((violation) => violation.message)
        .map((violation) => [violation.field, violation.message as string]),
    );
  }

  is(...codes: (ErrorCode | string)[]): boolean {
    return codes.includes(this.code);
  }

  /** Whether retrying the identical request could plausibly succeed. */
  get retryable(): boolean {
    return this.status === 429 || this.status === 503 || this.status >= 500;
  }
}

/** Thrown when the response does not match its schema - a contract drift, not a user error. */
export class SchemaMismatchError extends Error {
  constructor(
    readonly path: string,
    readonly issues: z.ZodIssue[],
  ) {
    super(`Response from ${path} did not match its schema`);
    this.name = 'SchemaMismatchError';
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, unknown>;
  headers?: Record<string, string>;
  /** Forwarded to `fetch`; also used by Next.js for revalidation and tagging. */
  cache?: RequestCache;
  next?: { revalidate?: number | false; tags?: string[] };
  signal?: AbortSignal;
  /** Skips the automatic refresh-and-retry on a 401. Used by the auth calls themselves. */
  skipAuthRefresh?: boolean;
}

export interface ClientConfig {
  baseUrl: string;
  /** Returns the current access token, or null when signed out. */
  getAccessToken?: () => string | null | undefined;
  /** Called once on a 401; returns true if a new token was obtained and the call should be retried. */
  refreshAccessToken?: () => Promise<boolean>;
  getLocale?: () => string;
  /** Guest cart identifier, sent as `X-Session-Id`. */
  getSessionId?: () => string | null | undefined;
  /** Sends cookies - required in the browser for the HttpOnly refresh cookie. */
  credentials?: RequestCredentials;
  defaultHeaders?: Record<string, string>;
}

/**
 * The typed HTTP client.
 *
 * Every response is parsed through a Zod schema before it reaches application code. That is the
 * point of this layer: without it, a field the backend renamed surfaces as `undefined` three
 * components deep, at render time, in production. Parsing here turns that into a single loud error
 * at the boundary, with the exact path that drifted.
 */
export class ApiClient {
  constructor(private readonly config: ClientConfig) {}

  async request<T>(
    path: string,
    schema: z.ZodType<T>,
    options: RequestOptions = {},
  ): Promise<T> {
    const response = await this.send(path, options);
    return this.parse(path, schema, response);
  }

  /** For endpoints that answer 204 with no body. */
  async requestVoid(path: string, options: RequestOptions = {}): Promise<void> {
    await this.send(path, options);
  }

  private async send(path: string, options: RequestOptions, isRetry = false): Promise<Response> {
    const url = this.buildUrl(path, options.query);
    const headers = this.buildHeaders(options);

    const response = await fetch(url, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      credentials: this.config.credentials ?? 'include',
      cache: options.cache,
      signal: options.signal,
      ...(options.next ? { next: options.next } : {}),
    });

    if (response.ok) {
      return response;
    }

    // A 401 on an access token that simply aged out is the common case, not an error worth
    // surfacing: refresh once and replay. Guarded against loops by `isRetry`, and skipped for the
    // auth endpoints themselves, where a 401 genuinely means bad credentials.
    if (
      response.status === 401 &&
      !isRetry &&
      !options.skipAuthRefresh &&
      this.config.refreshAccessToken
    ) {
      const refreshed = await this.config.refreshAccessToken();
      if (refreshed) {
        return this.send(path, options, true);
      }
    }

    throw await this.toApiError(response);
  }

  private async parse<T>(path: string, schema: z.ZodType<T>, response: Response): Promise<T> {
    if (response.status === 204) {
      return undefined as T;
    }

    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      throw new SchemaMismatchError(path, []);
    }

    const result = schema.safeParse(payload);
    if (!result.success) {
      // Logged rather than silently coerced: a schema mismatch means the client and the API have
      // drifted, and every downstream assumption about this data is now suspect.
      console.error(`[api-client] schema mismatch on ${path}`, result.error.issues);
      throw new SchemaMismatchError(path, result.error.issues);
    }
    return result.data;
  }

  private async toApiError(response: Response): Promise<ApiError> {
    try {
      const body = await response.json();
      const parsed = problemDetailSchema.safeParse(body);

      if (parsed.success) {
        return new ApiError(parsed.data);
      }
      return new ApiError({ status: response.status, detail: response.statusText });
    } catch {
      // A gateway timeout or a proxy error page is not JSON at all.
      return new ApiError({ status: response.status, detail: response.statusText });
    }
  }

  private buildUrl(path: string, query?: Record<string, unknown>): string {
    const url = new URL(path.startsWith('http') ? path : `${this.config.baseUrl}${path}`);

    if (query) {
      for (const [key, value] of Object.entries(query)) {
        if (value === undefined || value === null || value === '') continue;

        // Repeated parameters rather than a comma-joined string: `?brandSlug=a&brandSlug=b` is what
        // Spring binds to a List, and it survives values that contain commas.
        if (Array.isArray(value)) {
          value.forEach((entry) => url.searchParams.append(key, String(entry)));
        } else {
          url.searchParams.set(key, String(value));
        }
      }
    }
    return url.toString();
  }

  private buildHeaders(options: RequestOptions): Record<string, string> {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      ...this.config.defaultHeaders,
      ...options.headers,
    };

    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }

    const locale = this.config.getLocale?.();
    if (locale) {
      headers['Accept-Language'] = locale;
    }

    const token = this.config.getAccessToken?.();
    if (token) {
      headers.Authorization = `Bearer ${token}`;
    }

    const sessionId = this.config.getSessionId?.();
    if (sessionId) {
      headers['X-Session-Id'] = sessionId;
    }

    return headers;
  }
}

/**
 * Generates an idempotency key.
 *
 * Required on checkout, payment, and refund calls. The key must be stable across retries of the
 * *same* logical attempt - generate it once when the customer presses "Pay", not on each retry, or
 * the guarantee is lost precisely when it is needed.
 */
export function newIdempotencyKey(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2, 15)}`;
}
