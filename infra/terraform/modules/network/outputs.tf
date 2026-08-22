output "network_id" {
  value = google_compute_network.main.id
}

output "network_name" {
  value = google_compute_network.main.name
}

output "subnet_id" {
  value = google_compute_subnetwork.gke.id
}

output "subnet_name" {
  value = google_compute_subnetwork.gke.name
}

output "pods_range_name" {
  value = "pods"
}

output "services_range_name" {
  value = "services"
}

output "private_vpc_connection" {
  description = "Cloud SQL and Memorystore must depend on this, or they will be created before the peering exists and fail."
  value       = google_service_networking_connection.private_vpc.id
}

output "ingress_ip" {
  value = google_compute_global_address.ingress.address
}

output "ingress_ip_name" {
  description = "Referenced by the Gateway's NamedAddress"
  value       = google_compute_global_address.ingress.name
}

output "nat_ips" {
  description = "Give these to payment providers that allow-list by source address."
  value       = google_compute_address.nat[*].address
}
