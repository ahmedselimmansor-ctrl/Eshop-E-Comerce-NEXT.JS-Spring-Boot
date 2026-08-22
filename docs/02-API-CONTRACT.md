# Eshop — API Contract

Base URL: `/api/v1` · Content type: `application/json; charset=utf-8` · OpenAPI served at `/swagger-ui.html`, spec at `/v3/api-docs`.

## Cross-cutting

**Headers (request)**
| Header | Meaning |
|---|---|
| `Authorization: Bearer <accessToken>` | Auth. Absent = guest. |
| `Accept-Language: ar \| en` | Drives which `_ar`/`_en` fields are returned in localized DTOs. Default `ar`. |
| `X-Session-Id: <uuid>` | Guest cart identity. Required for guest cart routes. |
| `Idempotency-Key: <uuid>` | **Required** on `POST /checkout/orders`, `POST /payments/intent`, `POST /admin/orders/{id}/refund`. |
| `X-Request-Id` | Optional; echoed back and used as the correlation id in logs/traces. |

**Headers (response)** — `X-Request-Id`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After` (on 429).

**Pagination** — query `?page=0&size=20&sort=createdAt,desc`. `size` max 100.
```json
{
  "content": [ ... ],
  "page": { "number": 0, "size": 20, "totalElements": 137, "totalPages": 7, "first": true, "last": false }
}
```

**Errors** — RFC 9457 `application/problem+json`:
```json
{
  "type": "https://eshop.eg/errors/out-of-stock",
  "title": "Out of stock",
  "status": 409,
  "detail": "الكمية المطلوبة غير متوفرة",
  "instance": "/api/v1/checkout/orders",
  "code": "OUT_OF_STOCK",
  "requestId": "01J...",
  "timestamp": "2026-08-21T19:04:11Z",
  "errors": [ { "field": "items[0].quantity", "code": "MAX_AVAILABLE", "message": "المتاح 3 فقط", "rejectedValue": 5 } ]
}
```
Stable `code` values (frontend switches on these, never on `detail`):
`VALIDATION_ERROR` · `UNAUTHENTICATED` · `INVALID_CREDENTIALS` · `ACCOUNT_LOCKED` · `EMAIL_NOT_VERIFIED` · `TOKEN_EXPIRED` · `TOKEN_REUSE_DETECTED` · `FORBIDDEN` · `NOT_FOUND` · `CONFLICT` · `OUT_OF_STOCK` · `PRICE_CHANGED` · `CART_EMPTY` · `COUPON_INVALID` · `COUPON_EXPIRED` · `COUPON_USAGE_EXCEEDED` · `COUPON_MIN_ORDER` · `PAYMENT_FAILED` · `PAYMENT_ALREADY_PROCESSED` · `ORDER_NOT_CANCELLABLE` · `RATE_LIMITED` · `IDEMPOTENCY_CONFLICT` · `PAYLOAD_TOO_LARGE` · `UNSUPPORTED_MEDIA_TYPE` · `INTERNAL_ERROR` · `SERVICE_UNAVAILABLE`

**Money in JSON** — always `{ "amount": "1299.00", "currency": "EGP" }` (string amount, never float).

---

## 1. Auth — `/auth` (public, rate-limited)

| Method | Path | Body → Response |
|---|---|---|
| POST | `/auth/register` | `{firstName,lastName,email,phone?,password,locale?,marketingOptIn?}` → `201 {user, tokens}` |
| POST | `/auth/login` | `{emailOrPhone,password,rememberMe?}` → `200 {user, tokens}` |
| POST | `/auth/refresh` | `{refreshToken?}` (or httpOnly cookie) → `200 {tokens}` |
| POST | `/auth/logout` | `{refreshToken?}` → `204` (revokes family) |
| POST | `/auth/forgot-password` | `{email}` → `202` (always 202, never leak existence) |
| POST | `/auth/reset-password` | `{token,newPassword}` → `204` |
| POST | `/auth/verify-email` | `{token}` → `204` |
| POST | `/auth/resend-verification` | `{email}` → `202` |
| GET | `/auth/oauth/google` | → `302` to Google |
| GET | `/auth/oauth/google/callback` | → `302` to frontend with one-time code |
| POST | `/auth/oauth/exchange` | `{code}` → `200 {user, tokens}` (mobile/SPA) |

`tokens` = `{ accessToken, refreshToken, tokenType: "Bearer", expiresIn: 900 }`.
Web clients additionally receive the refresh token as `Set-Cookie: refresh_token=...; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`.

## 2. Account — `/me` (authenticated)

`GET /me` · `PATCH /me` `{firstName,lastName,phone,locale,avatarUrl,marketingOptIn}` · `POST /me/change-password` `{currentPassword,newPassword}` · `DELETE /me` (soft delete, GDPR) · `GET /me/export` (data export → private GCS signed URL)
Addresses: `GET /me/addresses` · `POST /me/addresses` · `GET|PATCH|DELETE /me/addresses/{id}` · `POST /me/addresses/{id}/default?type=shipping|billing`
Notifications: `GET /me/notifications?unreadOnly=` · `POST /me/notifications/{id}/read` · `POST /me/notifications/read-all` · `GET /me/notifications/unread-count`
Devices: `POST /me/devices` `{token,platform,locale}` · `DELETE /me/devices/{token}`
Wishlist: `GET /me/wishlist` · `POST /me/wishlist/items` `{productId,variantId?}` · `DELETE /me/wishlist/items/{id}` · `POST /me/wishlist/items/{id}/move-to-cart`

## 3. Catalog (public)

| Method | Path | Notes |
|---|---|---|
| GET | `/categories?flat=true` | Flat list |
| GET | `/categories/tree` | Nested tree, cached 10 min |
| GET | `/categories/{slug}` | With breadcrumb ancestors |
| GET | `/brands?featured=&page=` | |
| GET | `/brands/{slug}` | |
| GET | `/attributes?filterable=true` | For filter UI |
| GET | `/products` | Filters below |
| GET | `/products/{slug}` | Full detail: variants, images, attributes, stock, rating breakdown |
| GET | `/products/{id}/related` | Same category/brand, limit 12 |
| GET | `/products/{id}/frequently-bought` | Co-purchase, limit 4 |
| GET | `/products/{id}/reviews?rating=&sort=` | Approved only |
| GET | `/products/featured` · `/products/new-arrivals` · `/products/best-sellers` | Cached 5 min |

`GET /products` query params:
`q` · `categorySlug` (includes descendants) · `brandSlug` (repeatable) · `minPrice` · `maxPrice` · `attr_<code>=<valueCode>` (repeatable) · `minRating` · `inStock=true` · `onSale=true` · `sort=relevance|price_asc|price_desc|newest|best_selling|rating` · `page` · `size`
Response adds a `facets` block:
```json
{ "content": [...], "page": {...},
  "facets": { "categories": [{"slug":"phones","nameAr":"موبايلات","count":42}],
              "brands": [...], "attributes": {"color":[{"code":"red","valueAr":"أحمر","count":8}]},
              "priceRange": {"min":"99.00","max":"54999.00"} } }
```

## 4. Search — `/search` (Elasticsearch)

`GET /search?q=&locale=&page=&size=` → same shape as `/products` with `highlights`
`GET /search/suggest?q=&limit=8` → `{ products:[{id,slug,nameAr,nameEn,imageUrl,price}], categories:[...], brands:[...], queries:["..."] }`
`GET /search/popular?limit=10` → trending queries
`POST /search/click` `{query,productId}` → `204` (relevance feedback, fire-and-forget)

## 5. Cart — `/cart` (guest via `X-Session-Id`, or authenticated)

`GET /cart` · `POST /cart/items` `{productId,variantId?,quantity}` · `PATCH /cart/items/{itemId}` `{quantity}` · `DELETE /cart/items/{itemId}` · `DELETE /cart` (clear) · `POST /cart/coupon` `{code}` · `DELETE /cart/coupon` · `POST /cart/merge` `{sessionId}` (called right after login) · `POST /cart/validate` (re-check stock+prices before checkout)

Cart response:
```json
{ "id":"...", "currency":"EGP", "itemCount":3,
  "items":[{"id":"...","productId":"...","variantId":null,"slug":"iphone-15",
            "nameAr":"...","nameEn":"...","imageUrl":"...","variantLabelAr":null,
            "unitPrice":{"amount":"1299.00","currency":"EGP"},"quantity":2,
            "lineTotal":{"amount":"2598.00","currency":"EGP"},
            "availableQuantity":7,"inStock":true,"priceChanged":false}],
  "coupon":{"code":"WELCOME10","type":"PERCENTAGE","discount":{"amount":"259.80","currency":"EGP"}},
  "totals":{"subtotal":{...},"discount":{...},"tax":{...},"shipping":{...},"grandTotal":{...}} }
```

## 6. Checkout & orders

| Method | Path | Notes |
|---|---|---|
| GET | `/shipping/methods?governorate=&subtotal=` | Available methods + computed cost |
| POST | `/checkout/quote` | `{addressId?|address, shippingMethodId, couponCode?}` → totals preview, no side effects |
| POST | `/checkout/orders` | **Idempotent.** `{shippingAddressId?|shippingAddress, billingAddressId?, shippingMethodId, paymentProvider: PAYMOB\|FAWRY\|COD, paymentMethod, couponCode?, customerNote?, guestEmail?, guestPhone?}` → `201 {order, payment}` |
| GET | `/me/orders?status=&page=` | |
| GET | `/me/orders/{orderNumber}` | |
| POST | `/me/orders/{orderNumber}/cancel` | `{reason}` — allowed only in `PENDING_PAYMENT`/`PAID`/`PROCESSING` |
| GET | `/me/orders/{orderNumber}/invoice` | → signed GCS PDF URL |
| POST | `/me/orders/{orderNumber}/reorder` | Rebuilds cart |
| POST | `/me/orders/{orderNumber}/returns` | `{items:[{orderItemId,quantity}],reason,note}` |
| GET | `/orders/track/{orderNumber}?email=` | Guest tracking |

`payment` in the `201` response is a discriminated union on `provider`:
```json
{ "provider":"PAYMOB","paymentId":"...","method":"CARD","status":"PENDING","iframeUrl":"https://accept.paymob.com/api/acceptance/iframes/..." }
{ "provider":"FAWRY","paymentId":"...","method":"KIOSK","status":"PENDING","referenceCode":"9876543","expiresAt":"..." }
{ "provider":"COD","paymentId":"...","method":"COD","status":"PENDING","codFee":{"amount":"20.00","currency":"EGP"} }
```

## 7. Payments

`GET /payments/{paymentId}/status` (owner only) — the frontend polls this after returning from the provider.
`POST /payments/{paymentId}/retry` — new attempt for a `FAILED` payment.
`POST /payments/webhooks/paymob` — HMAC verified, **no auth**, always returns `200` once persisted.
`POST /payments/webhooks/fawry` — signature (SHA-256) verified, **no auth**.

## 8. Reviews

`POST /products/{productId}/reviews` `{rating,title,body,mediaUrls?}` → `201` (status `PENDING`; verified-purchase auto-detected)
`PATCH /reviews/{id}` · `DELETE /reviews/{id}` (owner) · `POST /reviews/{id}/helpful` `{helpful:true}`

## 9. Admin — `/admin/**` (permission-guarded, every mutation audit-logged)

| Area | Endpoints |
|---|---|
| Dashboard | `GET /admin/dashboard/summary?range=7d` → revenue, orders, AOV, conversion, new customers, deltas · `GET /admin/dashboard/revenue-series?range=&granularity=` · `GET /admin/dashboard/top-products` · `GET /admin/dashboard/order-status-breakdown` · `GET /admin/dashboard/low-stock` · `GET /admin/dashboard/recent-orders` |
| Products | `GET/POST /admin/products` · `GET/PUT/DELETE /admin/products/{id}` · `POST /admin/products/{id}/duplicate` · `PATCH /admin/products/bulk` `{ids,action,value}` · `POST /admin/products/import` (CSV) · `GET /admin/products/export` · variants `GET/POST /admin/products/{id}/variants`, `PUT/DELETE /admin/variants/{id}` · images `POST/DELETE /admin/products/{id}/images`, `PATCH /admin/products/{id}/images/reorder` |
| Taxonomy | `/admin/categories` CRUD + `PATCH /admin/categories/reorder` · `/admin/brands` CRUD · `/admin/attributes` CRUD + `/admin/attributes/{id}/values` |
| Inventory | `GET /admin/inventory?lowStock=&warehouseId=` · `POST /admin/inventory/{itemId}/adjust` `{quantity,reason,note}` · `GET /admin/inventory/{itemId}/movements` · `POST /admin/inventory/bulk-adjust` · `/admin/warehouses` CRUD |
| Orders | `GET /admin/orders?status=&paymentStatus=&q=&from=&to=` · `GET /admin/orders/{id}` · `POST /admin/orders/{id}/status` `{status,note}` · `POST /admin/orders/{id}/fulfill` `{items,carrier,trackingNumber}` · `POST /admin/orders/{id}/refund` `{amount,reason,items?}` **idempotent** · `POST /admin/orders/{id}/notes` · `GET /admin/orders/export` · `GET /admin/orders/{id}/invoice` |
| Returns | `GET /admin/returns` · `POST /admin/returns/{id}/approve\|reject\|receive` |
| Customers | `GET /admin/customers?q=&status=` · `GET /admin/customers/{id}` (orders, LTV, addresses) · `POST /admin/customers/{id}/suspend\|activate` · `GET /admin/customers/export` |
| Promotions | `/admin/coupons` CRUD + `GET /admin/coupons/{id}/redemptions` · `/admin/promotions` CRUD |
| Reviews | `GET /admin/reviews?status=` · `POST /admin/reviews/{id}/approve\|reject` `{reason?}` · `POST /admin/reviews/{id}/reply` |
| Shipping | `/admin/shipping-methods` CRUD · `/admin/shipping-zones` CRUD |
| Users & roles | `/admin/users` CRUD · `POST /admin/users/{id}/roles` · `GET /admin/roles` · `POST /admin/roles` · `GET /admin/permissions` |
| Media | `POST /admin/media/upload-url` `{filename,contentType,folder}` → `{uploadUrl,publicUrl,objectKey}` (V4 signed PUT, 15 min) · `DELETE /admin/media` `{objectKey}` |
| Settings | `GET/PUT /admin/settings` · `GET /admin/settings/{key}` |
| Audit | `GET /admin/audit-logs?entityType=&entityId=&actorId=&from=&to=` |
| Search ops | `POST /admin/search/reindex` `{entity}` · `GET /admin/search/status` |

## 10. Ops (not under `/api/v1`)

`GET /actuator/health/liveness` · `GET /actuator/health/readiness` · `GET /actuator/prometheus` · `GET /actuator/info`
Liveness/readiness are unauthenticated and excluded from rate limiting; everything else under `/actuator` requires `SUPER_ADMIN` or an internal network source.

---

## Permission matrix (summary)

| Endpoint group | Required authority |
|---|---|
| `/admin/products/**` write | `product:write` |
| `/admin/orders/**` read | `order:read` |
| `POST /admin/orders/{id}/refund` | `order:refund` |
| `POST /admin/orders/{id}/fulfill` | `order:fulfill` |
| `/admin/customers/**` | `customer:read` / `customer:write` |
| `/admin/coupons/**` | `coupon:write` |
| `/admin/reviews/*/approve` | `review:moderate` |
| `/admin/users/**`, `/admin/roles/**` | `user:write`, `role:write` |
| `/admin/settings/**` | `settings:write` |
| `/admin/audit-logs` | `audit:read` |
| `/admin/dashboard/**` | `report:read` |

Default role grants: `CUSTOMER` → none. `SUPPORT` → `order:read`, `customer:read`, `review:moderate`. `CATALOG_MANAGER` → all `product|category|brand|attribute|inventory|media` + `report:read`. `ORDER_MANAGER` → all `order:*` + `customer:read` + `report:read`. `ADMIN` → everything except `role:write`, `settings:write`. `SUPER_ADMIN` → all.

## Rate limit tiers

| Bucket | Limit |
|---|---|
| Anonymous (per IP) | 60 req/min |
| Authenticated (per user) | 300 req/min |
| `POST /auth/login` | 5 per 15 min per (IP + email) |
| `POST /auth/register`, `/auth/forgot-password` | 3 per hour per IP |
| `POST /checkout/orders`, `/payments/**` | 10 req/min per user |
| `GET /search*` | 120 req/min per IP |
| `/admin/**` | 1000 req/min per user |
| Webhooks | exempt (protected by signature verification instead) |
