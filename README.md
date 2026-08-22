# Eshop

A full-stack e-commerce platform built for the Egyptian market and designed to run on Google Cloud.

**Arabic-first with RTL** · **Paymob, Fawry & cash on delivery** · **Spring Boot · Next.js · Flutter · GKE**

---

## What this is

A production-shaped storefront, admin console, and mobile client over a single Spring Boot backend.
The decisions that shape it — a modular monolith rather than microservices, Arabic as the default
locale rather than an afterthought, cash on delivery as a first-class payment method rather than a
bolt-on — are argued in [`docs/00-ARCHITECTURE.md`](docs/00-ARCHITECTURE.md).

| Layer | Technology |
|---|---|
| Backend | Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Redis 7 · Elasticsearch 8 |
| Storefront | Next.js 15 (App Router) · React 19 · Tailwind CSS v4 · TanStack Query · Zustand · Zod |
| Admin | Next.js 15, sharing the storefront's typed API client |
| Mobile | Flutter 3 · Riverpod · go_router |
| Infrastructure | GKE Autopilot · Cloud SQL · Memorystore · Cloud CDN · Cloud Armor · Terraform |

---

## Running it locally

Docker is the only prerequisite. No local JDK, Maven, Node, or GCP account is needed.

```bash
git clone <repository-url> && cd Eshop-E-Comerce-NEXT.JS-Spring-Boot
cp .env.example .env
make dev
```

That starts PostgreSQL, Redis, Elasticsearch, Mailpit, a Cloud Storage emulator, and Jaeger. Then:

```bash
make api      # Spring Boot on :8080
make web      # storefront on :3000
make admin    # admin console on :3001
```

Or run everything in containers:

```bash
make up
```

| | |
|---|---|
| Storefront | http://localhost:3000 |
| Admin | http://localhost:3001 |
| API docs | http://localhost:8080/swagger-ui.html |
| Mail | http://localhost:8025 — every outbound email lands here |
| Traces | http://localhost:16686 |

Payment providers are unconfigured by default, so local checkout uses cash on delivery, which needs
no gateway. Set the `PAYMOB_*` and `FAWRY_*` variables in `.env` to exercise the real flows against
their sandboxes.

---

## Layout

```
backend/          Spring Boot API — package per domain, enforced by ArchUnit
  ├── common/       errors, Money, UUIDv7, outbox, idempotency
  ├── security/     JWT, RBAC, rate limiting, password policy
  ├── catalog/      products, variants, categories, brands, attributes
  ├── inventory/    stock levels, movements ledger, reservations
  ├── cart/         guest + authenticated carts, merge on sign-in
  ├── promotion/    coupons and rule-driven promotions
  ├── order/        checkout, order state machine, shipments, returns
  ├── payment/      Paymob · Fawry · COD behind one provider interface
  ├── search/       Elasticsearch indexing and faceted query
  └── ...           media, notification, review, settings, admin

web/
  ├── packages/api-client/   Zod schemas + typed client, shared by both apps
  ├── apps/storefront/       customer-facing, ar/en with full RTL
  └── apps/admin/            operations console

mobile/           Flutter client
infra/
  ├── terraform/    GCP infrastructure as code
  ├── k8s/          Kustomize base + dev/staging/prod overlays
  └── docker/       multi-stage build files
docs/             architecture, data model, API contract, conventions
```

---

## Documentation

| | |
|---|---|
| [Architecture](docs/00-ARCHITECTURE.md) | System design, GCP topology, availability and security model |
| [Data model](docs/01-DATA-MODEL.md) | Every table, with the reasoning behind the shape |
| [API contract](docs/02-API-CONTRACT.md) | Endpoints, error codes, rate limits, permissions |
| [Conventions](docs/03-CONVENTIONS.md) | Pinned versions and the rules every module follows |

---

## A few decisions worth knowing before reading the code

**Stock is reserved, never decremented, at checkout.** An order that is never paid must not destroy
inventory. Checkout takes a 30-minute hold under `SELECT … FOR UPDATE`; payment converts it to a
sale, and a sweeper releases anything abandoned. This is what stops a flash sale from being drained
by carts nobody intends to pay for.

**Prices are re-read from the catalog inside the checkout transaction.** The cart's stored prices
are a display cache. A price that moved since the item was added surfaces as `PRICE_CHANGED` and the
customer confirms — never silently honoured, never silently raised.

**Checkout and refunds require an `Idempotency-Key`.** A customer on a flaky mobile connection taps
"Pay", the request succeeds, the response is lost, and the app retries. Without the key, that is two
orders and two charges.

**Payment webhooks are stored before they are acted on.** The unique `(provider, event_id)`
constraint is the idempotency mechanism — providers retry aggressively, and processing a capture
twice would commit stock twice and send two confirmations.

**The access token lives in memory; the refresh token is an HttpOnly cookie.** An XSS that reaches
the page gets at most a 15-minute token, not a 30-day session. Refresh tokens rotate on every use,
and replaying a spent one revokes the entire family.

**Elasticsearch returns ids, not documents.** Cards are hydrated from PostgreSQL, so a price edit is
visible immediately even while the index lags — and when the cluster is unavailable, listings fall
back to PostgreSQL rather than the storefront going down.

**Card data never touches this system.** Paymob's iframe and Fawry's hosted flow collect the PAN.
What is stored is a provider id, the brand, and the last four digits.

---

## Common tasks

```bash
make test              # every test suite
make test-integration  # against real Postgres, Redis, Elasticsearch via Testcontainers
make typecheck         # web workspace
make audit             # dependency vulnerabilities + SBOM
make db-shell          # psql against the local database
make db-reset          # drop, recreate, re-migrate
make images            # build the container images
make k8s-diff ENV=prod # preview the rendered Kubernetes manifests
make tf-plan ENV=prod  # Terraform plan
make help              # everything else
```

---

## Deploying

Infrastructure first, then the application:

```bash
cd infra/terraform/envs/prod
cp terraform.tfvars.example terraform.tfvars   # fill in project id and authorised networks
terraform init && terraform apply
```

Then point DNS at the `ingress_ip` output, populate Secret Manager (the External Secrets Operator
projects the values into the cluster), and let CI deploy — `.github/workflows/deploy.yml` builds
images, runs migrations as a Job, rolls the deployments, smoke-tests through the real load balancer,
and rolls back on failure.

Secrets never appear in this repository, in a container image, or in a CI variable. They live in
Secret Manager, and workloads reach Google services through Workload Identity, so there is no
service-account key to leak.

---

## Status

The backend is complete and compiles: migrations, security, catalog, inventory, cart, promotions,
checkout, orders, payments, search, media, notifications, and the admin API. The storefront covers
the full purchase path — home, listing with facets, product detail, cart, and checkout — in both
languages with RTL. Infrastructure covers Docker, Kustomize for three environments, Terraform for
GCP, and CI/CD.

The admin console and the Flutter client are scaffolded against the same typed contract; their
screens are the remaining build-out.
