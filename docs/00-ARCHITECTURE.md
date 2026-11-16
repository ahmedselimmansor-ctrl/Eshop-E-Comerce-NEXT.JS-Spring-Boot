# Eshop - Architecture (GCP)

> **This document is the single source of truth.** Every module in this repo MUST conform to it.
> Companion docs: [`01-DATA-MODEL.md`](01-DATA-MODEL.md), [`02-API-CONTRACT.md`](02-API-CONTRACT.md), [`03-CONVENTIONS.md`](03-CONVENTIONS.md).

## 1. Locked decisions

| Area | Decision |
|---|---|
| Locales | `ar` (default, RTL) + `en` (LTR). Every user-facing entity has `*_ar` / `*_en` columns. |
| Currency | `EGP` primary, multi-currency capable (`currency CHAR(3)` everywhere money is stored). |
| Payments | **Paymob** (card / wallet / installment), **Fawry** (kiosk / reference code), **COD** (cash on delivery). Behind a `PaymentProvider` SPI. |
| Auth | Self-hosted **JWT** in Spring Boot: 15-min access token + rotating refresh token, RBAC with fine-grained permissions, Google OAuth2 social login. |
| Backend shape | **Modular monolith** - one Spring Boot deployable, package-per-domain, enforced by ArchUnit. Split into services later without rewriting domain code. |
| Deployment | GKE Autopilot, one Deployment per app (api, web, admin), HPA on CPU + RPS. |

### Why a modular monolith and not microservices
The domain boundaries here (catalog / cart / order / payment) share transactional invariants - reserving stock and creating an order must be atomic. Microservices force that into sagas on day one for no benefit at this scale. The package structure below *is* the future service boundary: each domain owns its tables, exposes a Java interface, and never reaches into another domain's repositories. When one domain genuinely needs independent scaling, it lifts out cleanly.

## 2. Repository layout

```
.
├── docs/                       # this spec
├── backend/                    # Spring Boot 3.5 · Java 21 · Maven
│   └── src/main/java/com/eshop/
│       ├── EshopApplication.java
│       ├── common/             # errors, base entity, pagination, money, utils
│       ├── config/             # security, redis, elasticsearch, openapi, async, cors, observability
│       ├── security/           # JWT, filters, RBAC, permission evaluator, rate limiting
│       ├── user/               # users, roles, permissions, addresses, oauth
│       ├── catalog/            # products, variants, categories, brands, attributes
│       ├── inventory/          # warehouses, stock, reservations, movements
│       ├── cart/               # cart, cart items, guest→user merge
│       ├── promotion/          # coupons, promotions, redemptions
│       ├── order/              # orders, items, shipments, status machine
│       ├── payment/            # Paymob, Fawry, COD, webhooks, refunds
│       ├── search/             # Elasticsearch indexing + query
│       ├── media/              # GCS signed uploads
│       ├── review/             # product reviews + moderation
│       ├── notification/       # email, FCM push, in-app, Pub/Sub outbox
│       └── admin/              # admin-only controllers + reporting
├── web/                        # npm workspaces monorepo
│   ├── apps/storefront/        # Next.js 15 · customer-facing · ar/en RTL
│   ├── apps/admin/             # Next.js 15 · admin dashboard
│   └── packages/api-client/    # Zod schemas + typed fetch client (shared)
├── infra/
│   ├── terraform/              # GCP IaC (modules + envs)
│   ├── k8s/                    # Kustomize base + overlays
│   └── docker/                 # Dockerfiles
├── .github/workflows/          # CI/CD
└── docker-compose.yml          # full local stack
```

## 3. GCP topology

```
                          Internet
                             │
                 ┌───────────▼────────────┐
                 │  Cloud DNS (eshop.eg)  │
                 └───────────┬────────────┘
                             │
              ┌──────────────▼───────────────┐
              │ Global External HTTPS LB      │  ← managed TLS cert
              │  + Cloud CDN (static/media)   │
              │  + Cloud Armor (WAF + rate    │
              │    limit + geo + OWASP rules) │
              └───┬───────────┬───────────┬───┘
                  │           │           │
        /_next/*  │  /api/*   │  /media/* │
        (CDN)     │           │  (CDN → GCS bucket backend)
                  │           │
        ┌─────────▼───┐  ┌────▼─────────┐
        │  GKE Autopilot (regional, private nodes) │
        │  ┌─────────┐ ┌─────────┐ ┌──────────┐   │
        │  │ web     │ │ admin   │ │ api      │   │
        │  │ (Next)  │ │ (Next)  │ │ (Boot)   │   │
        │  │ HPA 2-20│ │ HPA 1-4 │ │ HPA 3-50 │   │
        │  └─────────┘ └─────────┘ └────┬─────┘   │
        │  ┌────────────────────────────▼──────┐  │
        │  │ ECK: Elasticsearch 8 (3 nodes)    │  │
        │  └───────────────────────────────────┘  │
        └───┬──────────────┬──────────────┬───────┘
            │ private IP   │              │
   ┌────────▼───────┐ ┌────▼─────────┐ ┌──▼──────────────┐
   │ Cloud SQL      │ │ Memorystore  │ │ Cloud Storage   │
   │ PostgreSQL 16  │ │ Redis 7 HA   │ │ media + backups │
   │ Regional HA    │ │ (cache+lock+ │ │ (CMEK, lifecycle│
   │ + read replica │ │  rate limit) │ │  + versioning)  │
   │ + PITR 7d      │ └──────────────┘ └─────────────────┘
   └────────────────┘
            │
   ┌────────▼─────────────────────────────────────────┐
   │ Pub/Sub (domain events) → Cloud Run subscribers  │
   │ Secret Manager · Artifact Registry · Cloud Build │
   │ Cloud Logging / Monitoring / Trace / Profiler    │
   └──────────────────────────────────────────────────┘
```

### Service mapping

| Concern | GCP service | Notes |
|---|---|---|
| Compute | **GKE Autopilot** (regional, `me-central1` or `europe-west1`) | Private nodes, Workload Identity, no node management. |
| Ingress / LB | **Global External Application LB** via GKE Gateway API | HTTP→HTTPS redirect, managed certs, path-based routing to 3 backends. |
| CDN | **Cloud CDN** on the LB | `/_next/static/*` immutable 1y; `/media/*` from GCS backend bucket; API responses `no-store` except public catalog GETs (`s-maxage=60, stale-while-revalidate=300`). |
| DB | **Cloud SQL PostgreSQL 16**, Enterprise Plus, regional HA | Private Service Connect only. Read replica for reporting. Automated backups + PITR. |
| Cache | **Memorystore for Redis 7** (Standard HA, 5GB) | Cache-aside, distributed locks, rate-limit buckets, cart TTL, session blacklist. |
| Search | **Elasticsearch 8 via ECK** on GKE | 3 data nodes, 2 replicas. Alternative: Elastic Cloud on GCP (documented in Terraform variable). |
| Object storage | **Cloud Storage** | `eshop-media-{env}` (public via CDN), `eshop-private-{env}` (invoices, exports), `eshop-backups-{env}`. Uniform bucket-level access, CMEK, lifecycle rules. |
| Secrets | **Secret Manager** + External Secrets Operator → K8s Secrets | No secret ever in git, env files, or container images. |
| Async | **Pub/Sub** | Transactional outbox → `order.placed`, `payment.captured`, `product.updated`, `stock.low`. Dead-letter topics. |
| Registry | **Artifact Registry** | Vulnerability scanning + Binary Authorization on prod. |
| CI/CD | **GitHub Actions** + Workload Identity Federation (keyless) | Plus Cloud Build triggers as fallback. |
| Observability | Cloud Logging, Monitoring, Trace, Profiler, Error Reporting | OpenTelemetry from all services. |
| WAF | **Cloud Armor** | OWASP preconfigured rules, per-IP rate limiting, bot management, geo rules. |

## 4. Request flow (the important ones)

### 4.1 Product listing (cached, fast path)
```
Browser → LB → Cloud CDN ─hit?→ return (s-maxage=60)
                   │miss
                   ▼
            Next.js RSC (web pod) → api pod /api/v1/products
                                        │
                              Redis cache-aside (key: products:list:{hash}, TTL 120s)
                                        │miss
                              Elasticsearch (filters, facets, sort)
                                        │
                              PostgreSQL (hydrate only if ES doc stale)
```

### 4.2 Checkout (the transactional core)
```
POST /api/v1/checkout/orders   [Idempotency-Key required]
  1. Validate cart is ACTIVE and belongs to caller
  2. Re-price every line from DB (NEVER trust client prices)
  3. Revalidate coupon (usage limits, dates, min order)
  4. Compute shipping from governorate + method, compute tax
  5. BEGIN TX
       - SELECT ... FOR UPDATE on inventory_items of every variant
       - assert available >= qty, else 409 OUT_OF_STOCK
       - quantity_reserved += qty, insert inventory_movement(RESERVATION)
       - insert order (PENDING_PAYMENT) + order_items (price snapshot)
       - insert outbox_event(order.placed)
       - mark cart CONVERTED
     COMMIT
  6. Create payment with provider (Paymob intent / Fawry ref / COD auto-confirm)
  7. Return { order, payment: { redirectUrl | iframeUrl | referenceCode } }

Webhook /payments/webhooks/{provider}
  1. Verify HMAC signature → 401 on mismatch
  2. INSERT INTO payment_webhook_events (event_id UNIQUE) → duplicate = 200 no-op
  3. Match order, transition PENDING_PAYMENT → PAID
  4. Convert reservation → SALE movement (on_hand -= qty, reserved -= qty)
  5. outbox_event(payment.captured) → email + push + admin notification
```
A scheduled job releases reservations for orders still `PENDING_PAYMENT` after 30 minutes.

### 4.3 Search indexing
Postgres write → transactional outbox row → Pub/Sub `product.updated` → indexer consumer → Elasticsearch bulk upsert. A nightly full reindex job repairs drift. **Elasticsearch is never the source of truth.**

## 5. Availability, scaling & recovery

| Objective | Target | How |
|---|---|---|
| Availability | 99.9% | Regional GKE (3 zones), Cloud SQL regional HA (sync standby, automatic failover), Memorystore Standard HA, LB health checks. |
| RTO | < 30 min | Terraform-reproducible infra; DB failover is automatic (~60s); PITR restore documented in `docs/06-RUNBOOKS.md`. |
| RPO | < 5 min | Cloud SQL PITR (WAL archiving), continuous. Nightly logical `pg_dump` to GCS with 30-day retention. |
| Scaling - web | HPA 2→20 pods @ 70% CPU | Stateless Next.js; CDN absorbs most load. |
| Scaling - api | HPA 3→50 pods @ 70% CPU + 1000 RPS/pod | Stateless; sticky nothing; sessions in Redis/JWT. |
| Scaling - DB | Vertical + read replica | Reporting/admin analytics routed to replica via `@Transactional(readOnly=true)` + `LazyConnectionDataSourceProxy`. |
| Burst events (flash sale) | Pre-warm | `minReplicas` bumped via scheduled HPA patch; Cloud Armor rate limits protect origin. |
| Graceful shutdown | `preStop` sleep 10s + `server.shutdown=graceful` (30s) | No dropped in-flight requests during rollout. |
| Rollout | `RollingUpdate maxSurge=1 maxUnavailable=0` + readiness gate | Zero-downtime. Argo Rollouts canary documented as an upgrade path. |
| PDB | `minAvailable: 50%` | Survives node drains. |
| Chaos/DR drill | Quarterly | Documented in runbooks. |

## 6. Security model

| Layer | Control |
|---|---|
| Edge | Cloud Armor: OWASP CRS (SQLi, XSS, LFI, RCE), per-IP rate limit 600 req/min, adaptive DDoS protection, ban duration 600s. |
| Transport | TLS 1.2+ everywhere, HSTS `max-age=63072000; includeSubDomains; preload`, no plaintext internal hops (mTLS optional via Istio). |
| AuthN | JWT RS256 (asymmetric, keys in Secret Manager, JWKS endpoint), 15-min access, 30-day rotating refresh with **reuse detection** (reuse ⇒ revoke whole family). Argon2id password hashing. |
| AuthZ | Roles + fine-grained permissions (`product:write`, `order:refund`, …). `@PreAuthorize("hasAuthority('order:refund')")` on every admin endpoint. Ownership checks on every `/me/*` resource. |
| Data access | Row-level ownership enforced in the service layer, never only in the controller. Admin reads are audit-logged. PII columns (`phone`, `email`) encrypted at rest by Cloud SQL CMEK; app-level AES-GCM envelope encryption for payment metadata. |
| Rate limiting | Bucket4j + Redis. Tiered: anonymous 60/min, authenticated 300/min, login 5/15min per IP+email, payment 10/min, admin 1000/min. Returns `429` + `Retry-After` + `X-RateLimit-*`. |
| Secrets | Secret Manager → External Secrets Operator → K8s Secret → env. Workload Identity, zero service-account JSON keys. Rotation runbook per secret. |
| Input | Bean Validation on every DTO, allow-list deserialization, size limits, `Content-Type` pinning, file upload MIME+magic-byte check, max 10MB. |
| Output | RFC 9457 `ProblemDetail`, no stack traces to clients, no SQL in messages, generic auth failures (never "user not found"). |
| Headers | CSP (nonce-based), `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `X-Frame-Options: DENY`. |
| CSRF | Web uses `SameSite=Strict` httpOnly refresh cookie + double-submit token for cookie-auth routes; mobile clients use Bearer only (CSRF N/A). |
| Supply chain | Dependabot, `mvn dependency-check`, Trivy image scan in CI, SBOM (CycloneDX), Binary Authorization on prod. |
| Audit | Every admin mutation → `audit_logs` (actor, action, entity, before/after JSON, IP, UA). Immutable, exported to BigQuery. |
| PCI | **No card data ever touches our servers.** Paymob iframe / Fawry hosted page only. We store provider IDs and last4 only. |

## 7. Observability

- **Logs** - JSON structured (`logstash-logback-encoder`), one line per request with `trace_id`, `span_id`, `user_id`, `route`, `status`, `duration_ms`. Auto-ingested by Cloud Logging on GKE. Log-based metrics + alerts on `ERROR` rate.
- **Metrics** - Micrometer → Prometheus endpoint → Google Managed Prometheus. RED metrics per endpoint + business metrics (`orders_placed_total`, `payment_failures_total`, `cart_abandonment_ratio`, `search_latency_seconds`).
- **Traces** - OpenTelemetry auto-instrumentation → Cloud Trace. Trace propagated browser → Next.js → API → DB/Redis/ES.
- **Errors** - Cloud Error Reporting + Sentry (frontend).
- **Dashboards** - Terraform-managed Cloud Monitoring dashboards: Golden Signals, Business KPIs, Infra.
- **Alerts** - p99 latency > 1s (5m), 5xx rate > 1%, DB CPU > 80%, Redis memory > 85%, payment failure rate > 5%, certificate expiry < 15d, HPA at max replicas.
- **SLOs** - availability 99.9%, p95 API latency < 300ms, checkout success rate > 98%. Error budget policy documented.

## 8. Environments

| Env | GKE | Cloud SQL | Redis | Domain |
|---|---|---|---|---|
| `dev` | Autopilot, 1 zone | db-g1-small, no HA | Basic 1GB | dev.eshop.local |
| `staging` | Autopilot, regional | Enterprise, HA | Standard 2GB | staging.eshop.eg |
| `prod` | Autopilot, regional | Enterprise Plus, HA + replica | Standard HA 5GB | eshop.eg |

Local development uses `docker-compose.yml` (Postgres, Redis, Elasticsearch, Kibana, MailHog, fake-gcs-server, Jaeger) - no GCP account needed.
