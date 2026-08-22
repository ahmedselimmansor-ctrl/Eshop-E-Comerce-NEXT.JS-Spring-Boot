variable "name_prefix" { type = string }
variable "environment" { type = string }
variable "region" { type = string }
variable "network_id" { type = string }

variable "private_vpc_connection" {
  description = "Peering connection the managed services allocate private IPs from. Passed in so Terraform orders creation correctly."
  type        = string
}

variable "db_tier" {
  description = "Cloud SQL machine type. db-custom-N-M is N vCPUs and M MB of RAM."
  type        = string
  default     = "db-custom-2-7680"
}

variable "db_replica_tier" {
  description = "Read replica tier. Usually smaller than the primary - it serves reporting, not checkout."
  type        = string
  default     = "db-custom-2-7680"
}

variable "db_disk_size_gb" {
  type    = number
  default = 50
}

variable "db_disk_limit_gb" {
  description = "Ceiling for autoresize. Bounds the bill if something writes unboundedly."
  type        = number
  default     = 500
}

variable "db_max_connections" {
  description = "Must exceed (pods x DB_POOL_MAX) plus headroom for migrations and admin sessions."
  type        = string
  default     = "400"
}

variable "redis_memory_gb" {
  type    = number
  default = 5
}

variable "cors_origins" {
  description = "Origins allowed to read from the media bucket. Never wildcard - that invites hotlinking billed to us."
  type        = list(string)
}
