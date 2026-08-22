/**
 * VPC and private connectivity.
 *
 * Everything that stores data - Cloud SQL, Memorystore - is reachable only over private IP. No
 * managed database in this project has a public address, so a leaked password is not on its own
 * enough to reach the data.
 */

resource "google_compute_network" "main" {
  name = "${var.name_prefix}-vpc"

  # Subnets are created explicitly below. Auto mode creates one per region with fixed ranges, which
  # both wastes address space and makes the layout impossible to reason about later.
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"
  description             = "Eshop ${var.environment} VPC"
}

resource "google_compute_subnetwork" "gke" {
  name          = "${var.name_prefix}-gke-subnet"
  network       = google_compute_network.main.id
  region        = var.region
  ip_cidr_range = var.gke_subnet_cidr

  # Alias IP ranges for GKE. Pods get real VPC addresses, which is what makes container-native load
  # balancing and VPC-level network policy work.
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = var.pods_cidr
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = var.services_cidr
  }

  # Lets nodes without external IPs reach Google APIs over the internal path rather than through
  # Cloud NAT.
  private_ip_google_access = true

  log_config {
    aggregation_interval = "INTERVAL_10_MIN"
    # 10% sampling: enough to investigate an incident, cheap enough to leave on permanently.
    flow_sampling = 0.1
    metadata      = "INCLUDE_ALL_METADATA"
  }
}

/**
 * Private Service Access.
 *
 * Reserves a range that Google's managed services allocate their private IPs from, then peers it
 * with our VPC. This is what allows Cloud SQL and Memorystore to exist with no public address at
 * all.
 */
resource "google_compute_global_address" "private_service_range" {
  name          = "${var.name_prefix}-private-service-range"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.main.id
}

resource "google_service_networking_connection" "private_vpc" {
  network                 = google_compute_network.main.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_service_range.name]

  # Without this, destroying the connection can leave orphaned peering that blocks the range from
  # being reused - a painful state to recover from by hand.
  deletion_policy = "ABANDON"
}

/**
 * Cloud NAT.
 *
 * GKE nodes have no external IPs, so outbound traffic to payment gateways and SMTP relays needs a
 * NAT. Egress therefore leaves from a small, known set of addresses, which is what lets a payment
 * provider allow-list us.
 */
resource "google_compute_router" "main" {
  name    = "${var.name_prefix}-router"
  network = google_compute_network.main.id
  region  = var.region
}

resource "google_compute_address" "nat" {
  count  = var.nat_ip_count
  name   = "${var.name_prefix}-nat-ip-${count.index}"
  region = var.region
}

resource "google_compute_router_nat" "main" {
  name   = "${var.name_prefix}-nat"
  router = google_compute_router.main.name
  region = var.region

  # Static addresses rather than auto-allocated: an allow-list at a payment provider has to name
  # specific IPs, and auto-allocation changes them.
  nat_ip_allocate_option = "MANUAL_ONLY"
  nat_ips                = google_compute_address.nat[*].self_link

  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  # Dynamic port allocation: a fixed per-VM port quota either wastes ports or throttles a busy node
  # during a traffic spike.
  enable_dynamic_port_allocation = true
  min_ports_per_vm               = 64
  max_ports_per_vm               = 2048

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

/**
 * Firewall.
 *
 * The VPC's implied rules already deny all ingress; these add back only what is needed, and the
 * health-check rule is the one whose absence is hardest to diagnose - backends simply never turn
 * healthy, with no error anywhere.
 */
resource "google_compute_firewall" "allow_health_checks" {
  name        = "${var.name_prefix}-allow-health-checks"
  network     = google_compute_network.main.id
  description = "Google load balancer health checkers"
  direction   = "INGRESS"
  priority    = 1000

  # Fixed, documented Google ranges - not arbitrary.
  source_ranges = ["35.191.0.0/16", "130.211.0.0/22"]

  allow {
    protocol = "tcp"
    ports    = ["8080", "3000", "3001"]
  }
}

resource "google_compute_firewall" "allow_internal" {
  name          = "${var.name_prefix}-allow-internal"
  network       = google_compute_network.main.id
  direction     = "INGRESS"
  priority      = 1100
  source_ranges = [var.gke_subnet_cidr, var.pods_cidr]

  allow {
    protocol = "tcp"
  }
  allow {
    protocol = "udp"
  }
  allow {
    protocol = "icmp"
  }
}

resource "google_compute_firewall" "deny_all_ingress" {
  name        = "${var.name_prefix}-deny-all-ingress"
  network     = google_compute_network.main.id
  description = "Explicit default deny, logged"
  direction   = "INGRESS"
  # Lowest priority: every allow rule above wins. Stated explicitly rather than relying on the
  # implied rule, because the implied one produces no logs - and an unexplained connection failure
  # with no log entry is a bad afternoon.
  priority = 65534

  source_ranges = ["0.0.0.0/0"]

  deny {
    protocol = "all"
  }

  log_config {
    metadata = "INCLUDE_ALL_METADATA"
  }
}

# Reserved up front so the DNS record can be created before the Gateway exists, and survives the
# Gateway being recreated.
resource "google_compute_global_address" "ingress" {
  name         = "${var.name_prefix}-global-ip"
  address_type = "EXTERNAL"
  ip_version   = "IPV4"
}
