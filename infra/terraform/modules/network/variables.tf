variable "name_prefix" {
  description = "Prefix for every resource name, e.g. eshop-prod"
  type        = string
}

variable "environment" {
  description = "dev | staging | prod"
  type        = string
}

variable "region" {
  description = "GCP region. Keep the database, cache, and cluster in the same one — cross-region latency is charged to every query."
  type        = string
}

variable "gke_subnet_cidr" {
  description = "Primary range for GKE nodes"
  type        = string
  default     = "10.10.0.0/20"
}

variable "pods_cidr" {
  description = "Secondary range for pod IPs. Sized generously: it cannot be widened after the cluster is created, and running out means rebuilding the cluster."
  type        = string
  default     = "10.20.0.0/14"
}

variable "services_cidr" {
  description = "Secondary range for ClusterIP services"
  type        = string
  default     = "10.24.0.0/20"
}

variable "nat_ip_count" {
  description = "Static NAT addresses. More than one only helps if port exhaustion is a real concern; each is billable."
  type        = number
  default     = 1
}
