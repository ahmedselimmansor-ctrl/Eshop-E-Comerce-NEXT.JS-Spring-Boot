variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "project_number" {
  description = "GCP project number, needed to name Google-managed service agents in IAM bindings."
  type        = string
}

variable "region" {
  description = "Primary region. me-central1 (Doha) is the closest to Egypt; europe-west1 is the usual alternative when a service is not yet available there."
  type        = string
  default     = "europe-west1"
}

variable "authorized_networks" {
  description = "CIDRs allowed to reach the GKE control plane — office egress and the CI runner."
  type        = list(object({ cidr = string, name = string }))
  default     = []
}
