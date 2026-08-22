output "ingress_ip" {
  description = "Point the A records for eshop.eg and admin.eshop.eg at this."
  value       = module.network.ingress_ip
}

output "nat_ips" {
  description = "Give these to Paymob and Fawry if they allow-list by source address."
  value       = module.network.nat_ips
}

output "cluster_name" {
  value = module.gke.cluster_name
}

output "registry_url" {
  value = module.gke.registry_url
}

output "db_connection_name" {
  value = module.data.db_connection_name
}

output "api_service_account_email" {
  description = "Goes into the iam.gke.io/gcp-service-account annotation on the eshop-api ServiceAccount."
  value       = module.gke.api_service_account_email
}

output "kubectl_config_command" {
  value = "gcloud container clusters get-credentials ${module.gke.cluster_name} --region ${var.region} --project ${var.project_id}"
}
