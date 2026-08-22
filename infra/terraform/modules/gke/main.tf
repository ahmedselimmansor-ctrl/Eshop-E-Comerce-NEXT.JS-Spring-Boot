/**
 * GKE Autopilot.
 *
 * Autopilot rather than Standard: node pools, machine types, autoscaling profiles, and OS patching
 * are all decisions this project has no reason to make. Autopilot bills per pod request rather than
 * per node, which for a workload that scales between 3 and 50 pods is both cheaper and one fewer
 * dimension to get wrong. It also enforces Workload Identity, shielded nodes, and a restricted node
 * OS by default - controls that on Standard have to be configured and then kept configured.
 *
 * The trade-off is less control: no DaemonSets that need host access, no privileged pods, no custom
 * node images. Nothing here needs any of those.
 */

resource "google_container_cluster" "main" {
  name     = "${var.name_prefix}-gke"
  location = var.region

  enable_autopilot = true

  network    = var.network_id
  subnetwork = var.subnet_id

  ip_allocation_policy {
    cluster_secondary_range_name  = var.pods_range_name
    services_secondary_range_name = var.services_range_name
  }

  private_cluster_config {
    # Nodes have no external addresses; outbound traffic goes through Cloud NAT.
    enable_private_nodes = true

    # The control plane keeps a public endpoint, restricted by the authorised networks below.
    # A fully private endpoint would require a bastion or a VPN for every kubectl call and for CI,
    # which is worth it at a larger organisation and mostly friction at this one.
    enable_private_endpoint = false
    master_ipv4_cidr_block  = var.master_cidr
  }

  master_authorized_networks_config {
    dynamic "cidr_blocks" {
      for_each = var.authorized_networks
      content {
        cidr_block   = cidr_blocks.value.cidr
        display_name = cidr_blocks.value.name
      }
    }
  }

  # Binds Kubernetes service accounts to Google service accounts, so pods obtain credentials from
  # the metadata server. This is what removes service-account JSON keys from the system entirely.
  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  release_channel {
    # Production trails by a channel: a regression in a Kubernetes minor should be found somewhere
    # other than the store that takes the orders.
    channel = var.environment == "prod" ? "STABLE" : "REGULAR"
  }

  maintenance_policy {
    recurring_window {
      start_time = "2026-01-01T01:00:00Z" # 03:00 Cairo
      end_time   = "2026-01-01T05:00:00Z"
      recurrence = "FREQ=WEEKLY;BYDAY=TU,WE"
    }
  }

  # Encrypts Secret objects in etcd with a KMS key, so an etcd backup on its own is not enough to
  # read them.
  database_encryption {
    state    = "ENCRYPTED"
    key_name = var.kms_key_id
  }

  # Managed Prometheus: metrics reach Cloud Monitoring without running and scaling a Prometheus
  # server of our own.
  monitoring_config {
    managed_prometheus {
      enabled = true
    }
    enable_components = [
      "SYSTEM_COMPONENTS",
      "APISERVER",
      "SCHEDULER",
      "CONTROLLER_MANAGER",
      "STORAGE",
      "HPA",
      "POD",
      "DAEMONSET",
      "DEPLOYMENT",
    ]
  }

  logging_config {
    enable_components = ["SYSTEM_COMPONENTS", "WORKLOADS"]
  }

  security_posture_config {
    mode               = "BASIC"
    vulnerability_mode = "VULNERABILITY_BASIC"
  }

  # A cluster is not something to recreate casually - the Gateway, the certificates, and every
  # workload go with it.
  deletion_protection = var.environment == "prod"

  lifecycle {
    ignore_changes = [
      # Autopilot manages these itself; Terraform would otherwise show a diff on every plan.
      node_config,
      initial_node_count,
    ]
  }

  resource_labels = {
    environment = var.environment
    managed_by  = "terraform"
  }
}

/**
 * Workload identity for the API.
 *
 * The Google service account below is what the API pods act as. Its role bindings are the complete
 * list of what a compromised API pod could reach - which is why they are narrow and why none of
 * them is a project-level admin role.
 */
resource "google_service_account" "api" {
  account_id   = "eshop-api"
  display_name = "Eshop API (${var.environment})"
  description  = "Workload identity for the API pods"
}

resource "google_service_account_iam_member" "api_workload_identity" {
  service_account_id = google_service_account.api.name
  role               = "roles/iam.workloadIdentityUser"
  # Names the exact Kubernetes service account allowed to impersonate this one.
  member = "serviceAccount:${var.project_id}.svc.id.goog[eshop/eshop-api]"
}

resource "google_project_iam_member" "api_roles" {
  for_each = toset([
    "roles/cloudsql.client",
    "roles/redis.viewer",
    # Read only. Write access to secrets is a deployment concern, not a runtime one.
    "roles/secretmanager.secretAccessor",
    "roles/pubsub.publisher",
    "roles/pubsub.subscriber",
    "roles/cloudtrace.agent",
    "roles/monitoring.metricWriter",
    "roles/logging.logWriter",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.api.email}"
}

# Storage is granted per bucket rather than project-wide: `roles/storage.admin` at the project level
# would let the API delete the Terraform state bucket.
resource "google_storage_bucket_iam_member" "api_media" {
  bucket = var.media_bucket
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.api.email}"
}

resource "google_storage_bucket_iam_member" "api_private" {
  bucket = var.private_bucket
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.api.email}"
}

/**
 * Artifact Registry.
 *
 * Immutable tags: a tag, once pushed, cannot be moved. That makes `eshop/api:v1.4.2` mean exactly
 * one image forever, so a rollback returns to the artefact that was actually tested rather than to
 * whatever now sits behind that tag.
 */
resource "google_artifact_registry_repository" "images" {
  location      = var.region
  repository_id = "eshop"
  format        = "DOCKER"
  description   = "Eshop container images"

  docker_config {
    immutable_tags = true
  }

  cleanup_policies {
    id     = "keep-recent-releases"
    action = "KEEP"
    most_recent_versions {
      keep_count = 20
    }
  }

  cleanup_policies {
    id     = "delete-old-untagged"
    action = "DELETE"
    condition {
      tag_state  = "UNTAGGED"
      older_than = "604800s" # 7 days
    }
  }
}
