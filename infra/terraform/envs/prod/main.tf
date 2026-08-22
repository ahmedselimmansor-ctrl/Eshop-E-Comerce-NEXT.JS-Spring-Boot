/**
 * Production environment.
 *
 * Composes the modules and holds nothing else — every environment is the same wiring with
 * different sizes, which is what makes staging a meaningful rehearsal for production.
 */

terraform {
  required_version = ">= 1.9"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  backend "gcs" {
    # State lives in a versioned bucket, not on anyone's laptop. GCS provides locking, so two
    # concurrent applies cannot corrupt it. The bucket is created once, by hand, outside Terraform
    # — bootstrapping state storage with the tool that needs it is a chicken-and-egg problem.
    bucket = "eshop-terraform-state"
    prefix = "envs/prod"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

locals {
  environment = "prod"
  name_prefix = "eshop-prod"

  cors_origins = [
    "https://eshop.eg",
    "https://www.eshop.eg",
    "https://admin.eshop.eg",
  ]
}

# ---------------------------------------------------------------------- KMS --

# Key for etcd secret encryption. Kept in its own key ring with rotation, and deliberately not
# destroyable — losing it makes every encrypted Secret unreadable.
resource "google_kms_key_ring" "main" {
  name     = "${local.name_prefix}-keyring"
  location = var.region
}

resource "google_kms_crypto_key" "gke" {
  name            = "gke-etcd"
  key_ring        = google_kms_key_ring.main.id
  rotation_period = "7776000s" # 90 days

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_kms_crypto_key_iam_member" "gke" {
  crypto_key_id = google_kms_crypto_key.gke.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = "serviceAccount:service-${var.project_number}@container-engine-robot.iam.gserviceaccount.com"
}

# ------------------------------------------------------------------ modules --

module "network" {
  source = "../../modules/network"

  name_prefix  = local.name_prefix
  environment  = local.environment
  region       = var.region
  nat_ip_count = 2
}

module "data" {
  source = "../../modules/data"

  name_prefix            = local.name_prefix
  environment            = local.environment
  region                 = var.region
  network_id             = module.network.network_id
  private_vpc_connection = module.network.private_vpc_connection

  # Four vCPUs, 15 GB. Sized for the connection count rather than for CPU: 50 API pods with a pool
  # of 30 is well past what a smaller instance's max_connections allows.
  db_tier            = "db-custom-4-15360"
  db_replica_tier    = "db-custom-2-7680"
  db_disk_size_gb    = 100
  db_disk_limit_gb   = 1000
  db_max_connections = "1000"

  redis_memory_gb = 5
  cors_origins    = local.cors_origins
}

module "gke" {
  source = "../../modules/gke"

  name_prefix         = local.name_prefix
  environment         = local.environment
  region              = var.region
  project_id          = var.project_id
  network_id          = module.network.network_id
  subnet_id           = module.network.subnet_id
  pods_range_name     = module.network.pods_range_name
  services_range_name = module.network.services_range_name
  media_bucket        = module.data.media_bucket
  private_bucket      = module.data.private_bucket
  kms_key_id          = google_kms_crypto_key.gke.id

  authorized_networks = var.authorized_networks
}

# --------------------------------------------------------------- Cloud Armor --

/**
 * WAF and rate limiting at the edge.
 *
 * Rules run before a request reaches a pod, so an attack costs Google's capacity rather than ours.
 * The application's own rate limiter is still in place and is finer-grained — this is the coarse
 * outer layer, sized to stop volumetric abuse rather than to enforce per-endpoint policy.
 */
resource "google_compute_security_policy" "armor" {
  name        = "eshop-armor-policy"
  description = "OWASP rules and rate limiting for the Eshop load balancer"

  # Rule ordering matters: lower priority evaluates first, and the first match wins.

  rule {
    priority    = 1000
    description = "Per-IP rate limit"
    action      = "rate_based_ban"

    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }

    rate_limit_options {
      conform_action = "allow"
      exceed_action  = "deny(429)"
      enforce_on_key = "IP"

      rate_limit_threshold {
        # Generous: a product page fires a dozen requests, and a shopper browsing quickly on a
        # shared connection should never see this. It exists to stop scraping and volumetric abuse,
        # not to police normal use.
        count        = 600
        interval_sec = 60
      }

      ban_duration_sec = 600
    }
  }

  rule {
    priority    = 2000
    description = "SQL injection (OWASP CRS)"
    action      = "deny(403)"
    match {
      expr {
        # Sensitivity 1 rather than the default 4: higher levels false-positive on legitimate
        # Arabic product descriptions and on JSON bodies containing quotes.
        expression = "evaluatePreconfiguredWaf('sqli-v33-stable', {'sensitivity': 1})"
      }
    }
  }

  rule {
    priority    = 2100
    description = "Cross-site scripting (OWASP CRS)"
    action      = "deny(403)"
    match {
      expr {
        expression = "evaluatePreconfiguredWaf('xss-v33-stable', {'sensitivity': 1})"
      }
    }
  }

  rule {
    priority    = 2200
    description = "Local and remote file inclusion"
    action      = "deny(403)"
    match {
      expr {
        expression = "evaluatePreconfiguredWaf('lfi-v33-stable', {'sensitivity': 1}) || evaluatePreconfiguredWaf('rfi-v33-stable', {'sensitivity': 1})"
      }
    }
  }

  rule {
    priority    = 2300
    description = "Remote code execution"
    action      = "deny(403)"
    match {
      expr {
        expression = "evaluatePreconfiguredWaf('rce-v33-stable', {'sensitivity': 1})"
      }
    }
  }

  /**
   * Payment webhooks bypass the WAF.
   *
   * Provider callbacks carry signed JSON that the CRS rules reliably misread as an injection
   * attempt — and a blocked callback means a paid order stays unpaid, which is a far worse outcome
   * than the risk this rule waives. The endpoints verify an HMAC over the raw body and are
   * idempotent by construction, so an unauthenticated request reaching them achieves nothing.
   */
  rule {
    priority    = 900
    description = "Allow payment provider callbacks"
    action      = "allow"
    match {
      expr {
        expression = "request.path.matches('/api/v1/payments/webhooks/')"
      }
    }
  }

  rule {
    priority    = 2147483647
    description = "Default allow"
    action      = "allow"
    match {
      versioned_expr = "SRC_IPS_V1"
      config {
        src_ip_ranges = ["*"]
      }
    }
  }

  adaptive_protection_config {
    layer_7_ddos_defense_config {
      # Learns normal traffic and flags anomalies rather than matching fixed signatures — the only
      # thing that catches a distributed attack that stays under the per-IP threshold.
      enable          = true
      rule_visibility = "STANDARD"
    }
  }
}
