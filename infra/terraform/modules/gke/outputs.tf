output "cluster_name" {
  value = google_container_cluster.main.name
}

output "cluster_endpoint" {
  value     = google_container_cluster.main.endpoint
  sensitive = true
}

output "cluster_location" {
  value = google_container_cluster.main.location
}

output "api_service_account_email" {
  description = "Annotate the eshop-api Kubernetes ServiceAccount with this."
  value       = google_service_account.api.email
}

output "registry_url" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}
