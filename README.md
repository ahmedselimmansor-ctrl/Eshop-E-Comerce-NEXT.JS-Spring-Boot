# Eshop

A full-stack e-commerce platform built for the Egyptian market and designed to run on Google Cloud.

**Arabic-first with RTL** · **Paymob, Fawry & cash on delivery** · **Spring Boot · Next.js · Flutter · GKE**

---

## Contents

- [What this is](#what-this-is)
- [System architecture](#system-architecture)
- [Backend module map](#backend-module-map)
- [Request lifecycle](#request-lifecycle)
- [Database design](#database-design)
- [Key flows](#key-flows)
- [Deployment topology](#deployment-topology)
- [CI/CD pipeline](#cicd-pipeline)
- [Running it locally](#running-it-locally)
- [Layout](#layout)
- [Decisions worth knowing before reading the code](#decisions-worth-knowing-before-reading-the-code)
- [Testing](#testing)
- [Common tasks](#common-tasks)
- [Deploying](#deploying)
- [Status](#status)

---

## What this is

A production-shaped storefront, admin console, and mobile client over a single Spring Boot backend.
The decisions that shape it, a modular monolith rather than microservices, Arabic as the default
locale rather than an afterthought, and cash on delivery as a first-class payment method rather than
a bolt-on, are argued in [`docs/00-ARCHITECTURE.md`](docs/00-ARCHITECTURE.md).

| Layer | Technology |
|---|---|
| Backend | Java 21 · Spring Boot 3.5 · PostgreSQL 16 · Redis 7 · Elasticsearch 8 |
| Storefront | Next.js 15 (App Router) · React 19 · Tailwind CSS v4 · TanStack Query · Zustand · Zod |
| Admin | Next.js 15, sharing the storefront's typed API client |
| Mobile | Flutter 3 · Riverpod · go_router |
| Infrastructure | GKE Autopilot · Cloud SQL · Memorystore · Cloud CDN · Cloud Armor · Terraform |

---

## System architecture

Three clients, one API. The storefront and the admin console share a single Zod-validated API
client, so a response shape that changes breaks the build rather than a page at runtime.

```mermaid
flowchart TB
    subgraph clients [Clients]
        SF["Storefront<br/>Next.js 15 · ar/en RTL"]
        AD["Admin console<br/>Next.js 15"]
        MB["Mobile<br/>Flutter · iOS/Android"]
    end

    subgraph edge [Edge]
        CDN["Cloud CDN"]
        ARMOR["Cloud Armor<br/>WAF + rate limiting"]
        LB["HTTPS Load Balancer"]
    end

    subgraph api [Spring Boot API]
        REST["REST · /api/v1<br/>OpenAPI documented"]
        SEC["Security filter chain<br/>JWT · RBAC · rate limit"]
        DOM["Domain modules"]
        JOBS["Scheduled jobs<br/>outbox drain · stock sweeper"]
    end

    subgraph data [Data]
        PG[("PostgreSQL 16<br/>system of record")]
        RD[("Redis 7<br/>cache · sessions · locks")]
        ES[("Elasticsearch 8<br/>search index")]
        GCS[("Cloud Storage<br/>product media")]
    end

    subgraph external [External services]
        PM["Paymob<br/>cards & wallets"]
        FW["Fawry<br/>cash at outlets"]
        MAIL["Email / SMS"]
    end

    SF --> CDN --> ARMOR --> LB
    AD --> ARMOR
    MB --> ARMOR
    LB --> SEC --> REST --> DOM

    DOM --> PG
    DOM --> RD
    DOM --> ES
    DOM --> GCS
    JOBS --> PG

    DOM -->|"charge"| PM
    DOM -->|"reference code"| FW
    PM -.->|"webhook"| REST
    FW -.->|"webhook"| REST
    JOBS --> MAIL

    style PG fill:#1d4ed8,stroke:#1e3a8a,color:#ffffff
    style RD fill:#b91c1c,stroke:#7f1d1d,color:#ffffff
    style ES fill:#b45309,stroke:#78350f,color:#ffffff
    style GCS fill:#15803d,stroke:#14532d,color:#ffffff
```

Elasticsearch is deliberately not on the critical path. It returns ids; the cards are hydrated from
PostgreSQL, so a price edit is visible immediately even while the index lags, and if the cluster is
unavailable, listings fall back to PostgreSQL rather than the storefront going down.

---

## Backend module map

A modular monolith. Each module owns its tables and exposes a service; nothing reaches into another
module's repositories. The layering rules below are enforced by ArchUnit tests, not by convention.

```mermaid
flowchart TB
    WEB["<b>web</b><br/>controllers · request and response DTOs"]
    SVC["<b>service</b><br/>@Transactional · owns the mapping to DTOs"]
    DOM["<b>domain</b><br/>JPA entities · repositories"]
    DB[("PostgreSQL")]

    COMMON["<b>common</b><br/>Money · Problem+JSON errors<br/>idempotency · outbox · security"]

    WEB --> SVC --> DOM --> DB
    COMMON -.-> WEB
    COMMON -.-> SVC
    COMMON -.-> DOM

    DOM -. "forbidden" .-> WEB

    style DB fill:#1d4ed8,stroke:#1e3a8a,color:#ffffff
    style COMMON fill:#475569,stroke:#1e293b,color:#ffffff
    linkStyle 6 stroke:#b91c1c,stroke-width:2px,color:#b91c1c
```

The dashed red edge is the one the ArchUnit suite exists to prevent. Two rules matter enough to be
tests:

- **No entity or repository may depend on the web layer.** A domain object that knows about a
  response DTO cannot be reused, and the dependency ends up pointing both ways.
- **No controller may inject a repository.** A controller holding a repository queries outside a
  transaction and maps the result after the session has closed. With `open-in-view` disabled that
  compiles perfectly and throws `LazyInitializationException` on every call.

And the modules themselves, with the dependencies that actually exist between them:

```mermaid
flowchart LR
    ADMIN["admin"]
    ORDER["order"]
    CART["cart"]
    PAYMENT["payment"]
    INVENTORY["inventory"]
    PROMOTION["promotion"]
    CATALOG["catalog"]
    SEARCH["search"]
    REVIEW["review"]
    SETTINGS["settings"]
    OUTBOX["outbox relay"]
    NOTIFICATION["notification"]

    ADMIN --> CATALOG
    ADMIN --> INVENTORY
    ADMIN --> ORDER
    ADMIN --> PAYMENT
    ADMIN --> REVIEW
    ADMIN --> SEARCH
    ADMIN --> SETTINGS

    ORDER --> CART
    ORDER --> CATALOG
    ORDER --> INVENTORY
    ORDER --> PROMOTION
    ORDER --> PAYMENT
    ORDER --> SETTINGS
    PAYMENT --> ORDER

    CART --> CATALOG
    CART --> INVENTORY
    CART --> PROMOTION

    SEARCH --> CATALOG
    SEARCH --> INVENTORY
    REVIEW --> CATALOG
    REVIEW --> SETTINGS
    CATALOG <--> INVENTORY

    ORDER -. "event" .-> OUTBOX
    PAYMENT -. "event" .-> OUTBOX
    OUTBOX --> NOTIFICATION

    style ORDER fill:#1d4ed8,stroke:#1e3a8a,color:#ffffff
    style CATALOG fill:#15803d,stroke:#14532d,color:#ffffff
    style OUTBOX fill:#b45309,stroke:#78350f,color:#ffffff
```

This is derived from the actual imports, not from intent. Three things it shows that are easy to get
wrong from memory:

- **`notification` has no inbound arrow from `order` or `payment`.** Nothing calls it directly.
  Events go to the outbox in the same transaction as the write, and the relay drains them, which is
  what stops a mail provider being briefly down from rolling back a captured payment.
- **`catalog` and `inventory` point at each other.** A product listing needs availability, and a
  stock row needs the product it counts.
- **`payment` and `order` also point both ways**, on purpose: an order starts a payment, and a
  captured payment moves the order.

---

## Request lifecycle

Every request passes the same chain before a controller sees it.

```mermaid
flowchart LR
    R["Request"] --> A["Cloud Armor<br/>WAF · IP rules"]
    A --> B["Rate limit filter<br/>Redis token bucket"]
    B --> C["JWT filter<br/>verify RS256, build principal"]
    C --> D["Locale filter<br/>Accept-Language to ar/en"]
    D --> E["Request logging<br/>trace id · request id"]
    E --> F["@PreAuthorize<br/>permission check"]
    F --> G["Controller"]
    G --> H["@Transactional service"]
    H --> I["Repository"]
    I --> J[("PostgreSQL")]

    H -.->|"on failure"| X["GlobalExceptionHandler<br/>RFC 9457 problem document"]

    style X fill:#b91c1c,stroke:#7f1d1d,color:#ffffff
    style J fill:#1d4ed8,stroke:#1e3a8a,color:#ffffff
```

The response mapping happens **inside** the transactional service, not in the controller. That is
what keeps lazy associations reachable, and it is the reason the controller layer is as thin as it
is.

---

## Database design

PostgreSQL 16, migrated by Flyway. Roughly fifty tables across six areas. The diagrams below split
them by concern; a single diagram of the whole schema is not readable.

Conventions throughout:

- **UUIDv7 primary keys.** Time-ordered, so they index like a sequence without leaking a row count.
- **Money is `numeric(12,2)` plus a currency column.** Never a float.
- **Soft deletes where history must resolve.** `deleted_at` on products and users, so an old order
  still renders.
- **`jsonb` for shapes that genuinely vary.** Order addresses are snapshotted as `jsonb` so editing
  an address does not rewrite the delivery record of a past order.

### Identity and access

```mermaid
erDiagram
    users ||--o{ addresses : "ships to"
    users ||--o{ user_roles : has
    roles ||--o{ user_roles : "granted by"
    roles ||--o{ role_permissions : grants
    permissions ||--o{ role_permissions : "granted in"
    users ||--o{ refresh_tokens : holds
    users ||--o{ oauth_accounts : "signs in with"
    users ||--o{ one_time_tokens : "verifies with"
    users ||--o{ device_tokens : "notified on"

    users {
        uuid id PK
        varchar email UK
        varchar phone
        varchar password_hash
        varchar status "ACTIVE SUSPENDED DELETED"
        boolean email_verified
        varchar locale "ar or en"
        timestamptz deleted_at
    }
    roles {
        uuid id PK
        varchar name UK "CUSTOMER SUPPORT ADMIN SUPER_ADMIN"
    }
    permissions {
        uuid id PK
        varchar name UK "resource:action"
    }
    refresh_tokens {
        uuid id PK
        uuid user_id FK
        uuid family_id "the rotation chain"
        uuid replaced_by_id FK "successor after rotation"
        varchar token_hash
        inet ip
        varchar user_agent
        timestamptz expires_at
        timestamptz revoked_at
    }
    addresses {
        uuid id PK
        uuid user_id FK
        varchar recipient_name
        varchar phone
        varchar governorate "Arabic, matches shipping zones"
        varchar city
        varchar street
        boolean is_default_shipping
        boolean is_default_billing
    }
```

Permissions are fine-grained (`order:refund`, `product:write`) and roles are bundles of them.
Endpoints check the permission, never the role, so adding a role does not mean touching controllers.

Refresh tokens form a chain through `parent_id`. Replaying a spent token revokes the whole family,
which turns a stolen token into a detected intrusion rather than a silent one.

### Catalog

```mermaid
erDiagram
    brands ||--o{ products : makes
    categories ||--o{ categories : "parent of"
    categories ||--o{ products : "primary for"
    products ||--o{ product_categories : "also in"
    categories ||--o{ product_categories : contains
    products ||--o{ product_variants : "sold as"
    products ||--o{ product_images : shows
    products ||--o{ product_attribute_values : "described by"
    attributes ||--o{ attribute_values : defines
    attribute_values ||--o{ product_attribute_values : used
    attribute_values ||--o{ variant_attribute_values : "distinguishes"
    product_variants ||--o{ variant_attribute_values : "differs by"

    products {
        uuid id PK
        varchar sku UK "unique where not deleted"
        varchar slug UK
        varchar name_ar "required"
        varchar name_en "optional"
        text description_ar
        text description_en
        uuid brand_id FK
        uuid primary_category_id FK
        varchar status "DRAFT ACTIVE ARCHIVED"
        numeric base_price
        numeric compare_at_price "struck through when on sale"
        numeric cost_price "internal only"
        numeric tax_rate
        boolean is_featured
        timestamptz published_at
        timestamptz deleted_at
    }
    categories {
        uuid id PK
        uuid parent_id FK
        varchar slug UK
        varchar path "materialised, kept by trigger"
        smallint level
    }
    product_variants {
        uuid id PK
        uuid product_id FK
        varchar sku UK
        numeric price
        boolean is_active
    }
```

Every translatable column exists twice, `_ar` and `_en`, with Arabic required and English optional.
That is the storefront's fallback order: a product with only an English name renders as a blank tile
to an Arabic visitor, who is the default audience.

Categories carry a materialised `path` maintained by a database trigger, so a breadcrumb or a
subtree query is one indexed prefix scan rather than a recursive walk.

### Inventory

```mermaid
erDiagram
    warehouses ||--o{ inventory_items : stocks
    products ||--o{ inventory_items : "counted in"
    product_variants ||--o{ inventory_items : "counted in"
    inventory_items ||--o{ inventory_movements : "changed by"
    inventory_items ||--o{ stock_reservations : "held by"
    orders ||--o{ stock_reservations : holds

    inventory_items {
        uuid id PK
        uuid product_id FK
        uuid variant_id FK "null for simple products"
        uuid warehouse_id FK
        int quantity_on_hand "physically present"
        int quantity_reserved "held by unpaid orders"
        int reorder_level
        bigint version "optimistic lock"
    }
    inventory_movements {
        uuid id PK
        uuid inventory_item_id FK
        varchar type "RECEIPT SALE ADJUSTMENT RETURN"
        int quantity "signed delta"
        int quantity_after "running balance"
        varchar reference_type "what caused it"
        uuid reference_id
        text note
        uuid created_by FK
    }
    stock_reservations {
        uuid id PK
        uuid inventory_item_id FK
        uuid order_id FK
        int quantity
        timestamptz expires_at "30 minutes"
    }
```

The distinction between the three numbers is the whole point:

```mermaid
flowchart LR
    OH["quantity_on_hand<br/>what is on the shelf"]
    RS["quantity_reserved<br/>held by unpaid orders"]
    AV["available<br/>on_hand minus reserved"]

    OH --> AV
    RS --> AV
    AV -->|"decides"| BUY["Can a customer buy this?"]

    style AV fill:#15803d,stroke:#14532d,color:#ffffff
    style BUY fill:#1d4ed8,stroke:#1e3a8a,color:#ffffff
```

A screen or a query that reads `quantity_on_hand` alone is how a store oversells.

### Cart, orders and fulfilment

```mermaid
erDiagram
    users ||--o{ carts : owns
    carts ||--o{ cart_items : holds
    products ||--o{ cart_items : "added as"
    users ||--o{ orders : places
    orders ||--o{ order_items : contains
    products ||--o{ order_items : "sold as"
    orders ||--o{ order_status_history : "tracked by"
    orders ||--o{ shipments : "shipped in"
    shipments ||--o{ shipment_items : contains
    orders ||--o{ returns : "returned by"
    returns ||--o{ return_items : covers
    order_items ||--o{ return_items : "returned as"
    shipping_zones ||--o{ shipping_methods : offers
    shipping_methods ||--o{ orders : "delivered by"

    orders {
        uuid id PK
        varchar order_number UK "EG-YYMMDD-NNNNN"
        uuid user_id FK
        varchar status "see state machine"
        varchar payment_status
        varchar fulfillment_status
        jsonb shipping_address "snapshot, not a reference"
        jsonb billing_address
        numeric subtotal
        numeric discount_total
        numeric tax_total
        numeric shipping_total
        numeric cod_fee
        numeric grand_total
        varchar currency
        inet ip "fraud review"
        text admin_note "never customer facing"
        bigint version
    }
    order_items {
        uuid id PK
        uuid order_id FK
        uuid product_id FK
        uuid variant_id FK
        varchar sku "snapshot at purchase"
        varchar name_ar "snapshot at purchase"
        varchar name_en "snapshot at purchase"
        varchar image_url "snapshot at purchase"
        int quantity
        int fulfilled_quantity
        int refunded_quantity
        numeric unit_price "snapshot"
        numeric discount_amount
        numeric tax_amount
        numeric line_total
    }
    shipping_zones {
        uuid id PK
        jsonb governorates "Arabic names"
    }
```

Order items snapshot the name in both languages, the SKU, the image and the price at the moment of
purchase. A product renamed, reshot or repriced next month must not rewrite what a customer actually
bought.

The shipping address is `jsonb` rather than a foreign key for the same reason. Editing a saved
address should not silently change where a past order was delivered.

### Payments and promotions

```mermaid
erDiagram
    orders ||--o{ payments : "paid by"
    payments ||--o{ refunds : "refunded by"
    orders ||--o{ refunds : "refunded on"
    payment_webhook_events }o--|| payments : "confirms"
    coupons ||--o{ coupon_redemptions : "redeemed as"
    orders ||--o{ coupon_redemptions : uses
    coupons ||--o{ coupon_targets : "restricted to"

    payments {
        uuid id PK
        uuid order_id FK
        varchar provider "PAYMOB FAWRY COD"
        varchar method "CARD WALLET INSTALLMENT KIOSK COD"
        varchar status "PENDING CAPTURED FAILED"
        numeric amount
        varchar card_brand "no PAN, ever"
        varchar card_last4
        varchar failure_reason
    }
    payment_webhook_events {
        uuid id PK
        varchar provider
        varchar event_id "unique with provider"
        jsonb payload "stored before it is acted on"
        timestamptz processed_at
    }
    refunds {
        uuid id PK
        uuid payment_id FK
        numeric amount
        varchar reason
        varchar status
    }
    coupons {
        uuid id PK
        varchar code UK
        varchar type "PERCENT FIXED FREE_SHIPPING"
        numeric value
        numeric min_order_amount
        numeric max_discount_amount
        int usage_limit
        int usage_limit_per_user
        int used_count
        boolean first_order_only
        timestamptz starts_at
        timestamptz ends_at
        bigint version
    }
```

Card data never reaches this system. Paymob's iframe and Fawry's hosted flow collect the number;
what is stored is a provider id, the brand, and the last four digits.

The unique `(provider, event_id)` constraint on webhook events is the idempotency mechanism.
Providers retry aggressively, and processing a capture twice would commit stock twice and send two
confirmation emails.

### Reviews, search and operations

```mermaid
erDiagram
    products ||--o{ reviews : "reviewed in"
    users ||--o{ reviews : writes
    order_items ||--o| reviews : "verifies"
    reviews ||--o{ review_media : shows
    reviews ||--o{ review_votes : "voted on"
    users ||--o{ wishlists : keeps
    wishlists ||--o{ wishlist_items : holds
    users ||--o{ notifications : receives

    reviews {
        uuid id PK
        uuid product_id FK
        uuid user_id FK
        uuid order_item_id FK "proves purchase"
        smallint rating
        varchar status "PENDING APPROVED REJECTED"
        boolean is_verified_purchase
        text admin_reply
        varchar rejection_reason "internal"
    }
    search_queries {
        uuid id PK
        varchar query
        varchar normalized_query
        varchar locale
        uuid user_id FK
        int results_count "zero is the useful case"
        uuid clicked_product_id FK
    }
    settings {
        varchar key PK
        jsonb value "scalar, object or array"
        boolean is_public "served to every visitor"
    }
    idempotency_keys {
        varchar key PK
        varchar endpoint
        varchar request_hash "a different body is a client bug"
        jsonb response_body "replayed verbatim"
        varchar state
    }
```

`search_queries` exists for the rows with `results_count = 0`. Every one of them is either a product
the store should stock or a word the index should recognise.

### Platform tables

These have no foreign keys into the domain on purpose. Each one has to survive the transaction it
describes, so tying it to a row that may be rolled back would defeat the point.

```mermaid
erDiagram
    outbox_events {
        uuid id PK
        varchar aggregate_type "Order, Payment"
        uuid aggregate_id
        varchar event_type "order_paid, order_shipped"
        jsonb payload
        timestamptz published_at "null until drained"
        int attempts
        text last_error
    }
    audit_logs {
        uuid id PK
        uuid actor_id "no FK: the user may be deleted"
        varchar actor_email "captured at the time"
        varchar action
        varchar entity_type
        uuid entity_id
        jsonb before
        jsonb after
        inet ip
        varchar request_id "ties to the access log"
    }
    idempotency_keys {
        varchar key PK
        varchar endpoint
        varchar request_hash "a different body is a client bug"
        jsonb response_body "replayed verbatim"
        varchar state "IN_PROGRESS COMPLETED"
        timestamptz expires_at
    }
    promotions {
        uuid id PK
        varchar name_ar
        varchar name_en
        varchar type
        jsonb config "rule shape varies by type"
        int priority
        boolean is_stackable
        timestamptz starts_at
        timestamptz ends_at
    }
```

The outbox is what makes "capture the payment and send the confirmation" safe. The event is written
in the same transaction as the payment and drained separately, so an email provider that is briefly
down cannot roll back money that has already been taken.

`audit_logs` deliberately stores `actor_email` alongside `actor_id` and has no foreign key to
`users`. An audit record has to remain readable after the account it describes is deleted.

---

## Key flows

### Checkout

The single most consequential path in the system. Note where the idempotency key is claimed, and
that prices are re-read inside the transaction rather than trusted from the cart.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant API as Checkout API
    participant IDEM as Idempotency
    participant INV as Inventory
    participant PROMO as Promotions
    participant DB as PostgreSQL
    participant PAY as Payment provider

    C->>API: POST /checkout/orders + Idempotency-Key
    API->>IDEM: claim(key)
    alt key already completed
        IDEM-->>C: replay the original response
    end

    API->>DB: BEGIN
    API->>DB: re-read prices from catalog
    alt price moved since it was added to the cart
        API-->>C: 409 PRICE_CHANGED with the new figure
    end

    API->>INV: reserve stock (SELECT FOR UPDATE)
    alt not enough available
        API-->>C: 409 OUT_OF_STOCK
    end

    API->>PROMO: revalidate coupon under lock
    API->>DB: insert order, items, reservation
    API->>DB: COMMIT

    alt cash on delivery
        API-->>C: 201 order PENDING_PAYMENT
    else card or wallet
        API->>PAY: create payment intent
        PAY-->>API: hosted payment URL
        API-->>C: 201 order + redirect target
    end

    API->>IDEM: complete(key, response)
```

Stock is **reserved, never decremented**, at checkout. An order that is never paid must not destroy
inventory. The hold lasts thirty minutes; payment converts it into a sale, and a sweeper releases
anything abandoned.

### Payment confirmation

```mermaid
sequenceDiagram
    autonumber
    participant PAY as Paymob / Fawry
    participant API as Webhook endpoint
    participant DB as PostgreSQL
    participant OUT as Outbox
    participant MAIL as Email

    PAY->>API: POST /webhooks/{provider}
    API->>API: verify HMAC signature
    API->>DB: insert payment_webhook_events (provider, event_id)

    alt duplicate event id
        DB-->>API: unique violation
        API-->>PAY: 200 OK, already processed
    end

    API->>DB: BEGIN
    API->>DB: mark payment CAPTURED
    API->>DB: convert reservation into a sale
    API->>DB: order to PAID
    API->>OUT: enqueue order_paid event
    API->>DB: COMMIT
    API-->>PAY: 200 OK

    OUT->>MAIL: send confirmation (separate transaction)
```

The event is stored before it is acted on, and the outbox is drained separately. An email provider
that is briefly down cannot roll back a payment that has already been captured.

### Order state machine

This mirrors `OrderStatus.allowedTransitions()` exactly.

```mermaid
stateDiagram-v2
    [*] --> PENDING_PAYMENT: checkout

    PENDING_PAYMENT --> PAID: payment captured
    PENDING_PAYMENT --> CANCELLED: expired or cancelled
    PENDING_PAYMENT --> FAILED: payment declined

    PAID --> PROCESSING: picking started
    PAID --> SHIPPED: shipped directly
    PAID --> CANCELLED: cancelled before dispatch
    PAID --> REFUNDED: refunded before dispatch

    PROCESSING --> SHIPPED: fully fulfilled
    PROCESSING --> CANCELLED: cancelled before dispatch
    PROCESSING --> REFUNDED: refunded

    SHIPPED --> DELIVERED: courier confirms
    SHIPPED --> REFUNDED: came back

    DELIVERED --> PARTIALLY_REFUNDED: partial refund
    DELIVERED --> REFUNDED: full refund
    PARTIALLY_REFUNDED --> REFUNDED: remainder refunded

    CANCELLED --> [*]
    REFUNDED --> [*]
    DELIVERED --> [*]
    FAILED --> [*]
```

Illegal transitions are rejected with `409 INVALID_STATUS_TRANSITION`. A delivered order cannot go
back to processing. `DELIVERED`, `CANCELLED`, `REFUNDED` and `FAILED` are terminal; a customer may
cancel only from `PENDING_PAYMENT`, `PAID` or `PROCESSING`, since nothing that has left the warehouse
can be called back by a button.

A shipment that comes back is recorded as a refund on the order. The physical return has its own
approve and receive workflow on the `returns` table.

### Authentication and token refresh

```mermaid
sequenceDiagram
    autonumber
    participant C as Client
    participant API as Auth API
    participant DB as PostgreSQL

    C->>API: POST /auth/login
    API->>DB: verify password (bcrypt)
    API-->>C: access token (15 min, in memory)<br/>refresh token (30 days, HttpOnly cookie)

    Note over C: access token expires

    C->>API: request with expired token
    API-->>C: 401

    C->>API: POST /auth/refresh (cookie)
    API->>DB: look up token hash
    alt token already used
        API->>DB: revoke the entire family
        API-->>C: 401, sign in again
    else valid
        API->>DB: rotate, link parent_id
        API-->>C: new pair
    end
```

The access token lives in memory and the refresh token in an HttpOnly cookie. An XSS that reaches
the page gets at most a fifteen-minute token, not a thirty-day session.

On mobile there is no cookie jar, so the refresh token goes into the platform keychain, and refreshes
are serialised behind a lock. Without that lock, four concurrent 401s start four refreshes, three of
them present a token the first already rotated, and the backend correctly treats that as replay.

---

## Deployment topology

```mermaid
flowchart TB
    U["Users"] --> DNS["Cloud DNS"]
    DNS --> LB["Global HTTPS LB<br/>managed certificate"]
    LB --> ARMOR["Cloud Armor<br/>OWASP rules · rate limits"]
    ARMOR --> CDN["Cloud CDN<br/>static assets"]
    ARMOR --> ING["GKE Ingress"]

    subgraph gke ["GKE Autopilot"]
        ING --> SVC["Services"]
        SVC --> APIP["api pods<br/>HPA 3 to 20"]
        SVC --> WEBP["storefront pods"]
        SVC --> ADMP["admin pods"]
        JOB["migration Job<br/>runs before rollout"]
    end

    APIP --> SQL[("Cloud SQL<br/>PostgreSQL 16 HA")]
    APIP --> MEM[("Memorystore<br/>Redis")]
    APIP --> ESC[("Elasticsearch")]
    APIP --> BUCK[("Cloud Storage")]
    APIP --> SM["Secret Manager"]

    ESO["External Secrets Operator"] --> SM
    ESO --> gke
    APIP -.->|"Workload Identity"| SM

    style SQL fill:#1d4ed8,stroke:#1e3a8a,color:#ffffff
    style MEM fill:#b91c1c,stroke:#7f1d1d,color:#ffffff
    style SM fill:#6d28d9,stroke:#4c1d95,color:#ffffff
```

Secrets never appear in this repository, in a container image, or in a CI variable. They live in
Secret Manager, and workloads reach Google services through Workload Identity, so there is no
service-account key to leak.

---

## CI/CD pipeline

```mermaid
flowchart LR
    PR["Pull request"] --> CI

    subgraph CI ["CI (.github/workflows/ci.yml)"]
        direction TB
        BE["Backend<br/>compile · 56 tests<br/>real Postgres + Redis"]
        MIG["Migrations<br/>apply, then re-apply R__<br/>to prove idempotency"]
        WEB["Web<br/>typecheck · lint · 46 tests · build"]
        MOB["Mobile<br/>analyze · 23 tests"]
        SEC["Security<br/>secret scan · Trivy · SBOM"]
        INF["Infra<br/>terraform validate · kustomize build"]
    end

    CI -->|"all green"| MERGE["Merge to main"]
    MERGE --> DEPLOY

    subgraph DEPLOY ["Deploy (deploy.yml)"]
        direction TB
        IMG["Build images<br/>tagged with the git SHA"]
        DBJ["Run migrations as a Job"]
        ROLL["Roll deployments"]
        SMOKE["Smoke test through the real LB"]
        RB["Roll back on failure"]
    end

    IMG --> DBJ --> ROLL --> SMOKE
    SMOKE -.->|"fails"| RB

    style SEC fill:#b91c1c,stroke:#7f1d1d,color:#ffffff
    style RB fill:#b91c1c,stroke:#7f1d1d,color:#ffffff
```

Images are tagged with the git SHA and never `latest`. A tag that moves makes a rollback ambiguous.

---

## Running it locally

Docker is the only prerequisite. No local JDK, Maven, Node, or Flutter install is needed.

```bash
git clone https://github.com/ahmedselimmansor-ctrl/Eshop-E-Comerce-NEXT.JS-Spring-Boot.git
cd Eshop-E-Comerce-NEXT.JS-Spring-Boot
cp .env.example .env
make dev
```

That starts PostgreSQL, Redis, Elasticsearch, Mailpit, a Cloud Storage emulator, and Jaeger. Then:

```bash
make api      # Spring Boot on :8080
make web      # storefront on :3000
make admin    # admin console on :3001
```

Or run everything in containers with `make up`.

| | |
|---|---|
| Storefront | http://localhost:3000 |
| Admin | http://localhost:3001 |
| API docs | http://localhost:8080/swagger-ui.html |
| Mail | http://localhost:8025 (every outbound email lands here) |
| Traces | http://localhost:16686 |

Under the `local` and `dev` profiles the API seeds a demo catalog on startup: five brands, a
two-level category tree, and eight products with stock. It includes a draft product the storefront
must hide, one item out of stock, and one below its reorder level, so the empty and error states are
reachable without setting anything up.

Payment providers are unconfigured by default, so local checkout uses cash on delivery, which needs
no gateway. Set the `PAYMOB_*` and `FAWRY_*` variables in `.env` to exercise the real flows against
their sandboxes.

---

## Layout
```
backend/          Spring Boot API, package per domain, enforced by ArchUnit
  ├── common/       errors, Money, UUIDv7, outbox, idempotency
  ├── security/     JWT, RBAC, rate limiting, password policy
  ├── catalog/      products, variants, categories, brands, attributes
  ├── inventory/    stock levels, movements ledger, reservations
  ├── cart/         guest and authenticated carts, merge on sign-in
  ├── promotion/    coupons and rule-driven promotions
  ├── order/        checkout, order state machine, shipments, returns
  ├── payment/      Paymob · Fawry · COD behind one provider interface
  ├── search/       Elasticsearch indexing and faceted query
  └── ...           media, notification, review, settings, admin

web/
  ├── packages/api-client/   Zod schemas and typed client, shared by both apps
  ├── apps/storefront/       customer facing, ar/en with full RTL
  └── apps/admin/            operations console

mobile/           Flutter client
infra/
  ├── terraform/    GCP infrastructure as code
  ├── k8s/          Kustomize base plus dev/staging/prod overlays
  └── docker/       multi-stage build files
docs/             architecture, data model, API contract, conventions
```

| | |
|---|---|
| [Architecture](docs/00-ARCHITECTURE.md) | System design, GCP topology, availability and security model |
| [Data model](docs/01-DATA-MODEL.md) | Every table, with the reasoning behind the shape |
| [API contract](docs/02-API-CONTRACT.md) | Endpoints, error codes, rate limits, permissions |
| [Conventions](docs/03-CONVENTIONS.md) | Pinned versions and the rules every module follows |

---

## Decisions worth knowing before reading the code

**Stock is reserved, never decremented, at checkout.** An order that is never paid must not destroy
inventory. Checkout takes a thirty-minute hold under `SELECT ... FOR UPDATE`; payment converts it to
a sale, and a sweeper releases anything abandoned. This is what stops a flash sale from being drained
by carts nobody intends to pay for.

**Prices are re-read from the catalog inside the checkout transaction.** The cart's stored prices are
a display cache. A price that moved since the item was added surfaces as `PRICE_CHANGED` and the
customer confirms. Never silently honoured, never silently raised.

**Checkout and refunds require an `Idempotency-Key`.** A customer on a flaky mobile connection taps
"Pay", the request succeeds, the response is lost, and the app retries. Without the key, that is two
orders and two charges.

**Payment webhooks are stored before they are acted on.** The unique `(provider, event_id)`
constraint is the idempotency mechanism. Providers retry aggressively, and processing a capture twice
would commit stock twice and send two confirmations.

**The access token lives in memory; the refresh token is an HttpOnly cookie.** An XSS that reaches
the page gets at most a fifteen-minute token, not a thirty-day session. Refresh tokens rotate on
every use, and replaying a spent one revokes the entire family.

**Elasticsearch returns ids, not documents.** Cards are hydrated from PostgreSQL, so a price edit is
visible immediately even while the index lags, and when the cluster is unavailable, listings fall
back to PostgreSQL rather than the storefront going down.

**Card data never touches this system.** Paymob's iframe and Fawry's hosted flow collect the PAN.
What is stored is a provider id, the brand, and the last four digits.

**Response mapping happens inside the transaction.** `open-in-view` is disabled. A controller that
maps an entity returned by a service is reading a detached proxy, which compiles cleanly and throws
on every call. Services return finished DTOs.

---

## Testing

```bash
make test          # backend, web and mobile
make test-backend  # 56 tests, needs `make infra` running
make test-web      # typecheck, lint, and 46 unit tests
make test-mobile   # flutter analyze and 23 tests
```

The backend suite runs against a real PostgreSQL and Redis rather than mocks, because the defects it
exists to catch are the ones a mock cannot see: a `jsonb` column rejecting a value, a lazy
association read after its session closed, a migration that does not apply. It creates and uses its
own `eshop_test` database, so a run never touches local working data.

ArchUnit enforces the layering rules described above. They are tests rather than documentation
because every one of them was written after the rule had already been broken somewhere.

---

## Common tasks

```bash
make dev               # start local infrastructure
make test              # every test suite
make typecheck         # web workspace only
make audit             # dependency vulnerabilities and SBOM
make db-shell          # psql against the local database
make db-reset          # drop, recreate, re-migrate
make db-repair         # realign Flyway checksums, runs no SQL
make reindex           # rebuild the Elasticsearch product index
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
projects the values into the cluster), and let CI deploy. `.github/workflows/deploy.yml` builds
images, runs migrations as a Job, rolls the deployments, smoke-tests through the real load balancer,
and rolls back on failure.

---

## Status

**Backend.** Complete and tested: migrations, security, catalog, inventory, cart, promotions,
checkout, orders, payments, search, media, notifications, and the admin API. Fifty-six tests,
covering the checkout and refund paths, the store rules that live in settings, the admin API, JSON
round-tripping, and the layering rules.

**Storefront.** The full purchase path in both languages with RTL: home, listing with facets, product
detail, cart, checkout, account, orders, and wishlist.

**Admin console.** Dashboard, orders with fulfilment and refunds, products with a create and edit
form, inventory, customers, review moderation, search health, and settings. Every action is gated on
the permission its endpoint enforces.

**Mobile.** The full purchase path: home, search with debounced queries and sorting, category
listing, product detail, cart, checkout, order detail, account, addresses, and sign-in with
registration. `flutter analyze` is clean and 23 tests cover the parsing and the order state logic.
Widget behaviour is unverified, since there is no emulator in this environment.

**Infrastructure.** Docker, Kustomize for three environments, Terraform for GCP, and CI/CD.

**Tests.** 125 in total: 56 backend, 46 web, 23 mobile. All three run in CI.

Known gaps: no end-to-end or component tests on the web (the unit tests cover the API contract, the
formatting helpers and the listing-parameter clamping, not rendering); the refund path has not been
exercised against a real captured payment, since a cash-on-delivery order has nothing to refund and
the API correctly rejects it.
