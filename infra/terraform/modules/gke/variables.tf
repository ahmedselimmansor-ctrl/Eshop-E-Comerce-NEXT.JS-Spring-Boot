variable "name_prefix" { type = string }
variable "environment" { type = string }
variable "region" { type = string }
variable "project_id" { type = string }
variable "network_id" { type = string }
variable "subnet_id" { type = string }
variable "pods_range_name" { type = string }
variable "services_range_name" { type = string }
variable "media_bucket" { type = string }
variable "private_bucket" { type = string }

variable "master_cidr" {
  description = "Control plane range. Must not overlap any subnet or secondary range."
  type        = string
  default     = "172.16.0.0/28"
}

variable "kms_key_id" {
  description = "KMS key for etcd secret encryption"
  type        = string
}

variable "authorized_networks" {
  description = "Who may reach the control plane. Keep this to office egress and the CI runner - an open control plane is an open door."
  type        = list(object({ cidr = string, name = string }))
  default     = []
}
