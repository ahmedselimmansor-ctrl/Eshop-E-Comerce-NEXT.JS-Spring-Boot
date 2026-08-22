/**
 * The Eshop API client.
 *
 * Shared by the storefront and the admin dashboard so both speak to the same contract. Every
 * response is validated against a Zod schema at the boundary - see `http.ts` for why that matters
 * more than it might appear.
 */
export * from './schemas';
export { ApiClient, ApiError, SchemaMismatchError, newIdempotencyKey } from './http';
export type { ClientConfig, RequestOptions } from './http';
export { EshopApi } from './endpoints';
export { EshopAdminApi } from './admin-endpoints';

import { ApiClient, type ClientConfig } from './http';
import { EshopApi } from './endpoints';
import { EshopAdminApi } from './admin-endpoints';

/** Convenience factory: builds the low-level client and the typed facade in one call. */
export function createApi(config: ClientConfig): EshopApi {
  return new EshopApi(new ApiClient(config));
}

/**
 * The admin facade.
 *
 * Separate from {@link createApi} so the storefront cannot reach the privileged endpoints by
 * accident, and so its bundle never pulls in the admin schemas.
 */
export function createAdminApi(config: ClientConfig): EshopAdminApi {
  return new EshopAdminApi(new ApiClient(config));
}
