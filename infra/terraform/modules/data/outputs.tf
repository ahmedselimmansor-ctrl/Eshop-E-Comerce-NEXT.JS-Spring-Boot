output "db_private_ip" {
  value = google_sql_database_instance.main.private_ip_address
}

output "db_connection_name" {
  description = "For the Cloud SQL Auth Proxy and for gcloud sql connect."
  value       = google_sql_database_instance.main.connection_name
}

output "db_replica_private_ip" {
  value = var.environment == "prod" ? google_sql_database_instance.replica[0].private_ip_address : null
}

output "redis_host" {
  value = google_redis_instance.cache.host
}

output "redis_port" {
  value = google_redis_instance.cache.port
}

output "media_bucket" {
  value = google_storage_bucket.media.name
}

output "private_bucket" {
  value = google_storage_bucket.private.name
}

output "media_bucket_url" {
  value = "https://storage.googleapis.com/${google_storage_bucket.media.name}"
}

# Deliberately no output for the database password. It exists only in Secret Manager; surfacing it
# here would write it to the Terraform state file in plaintext.
output "db_password_secret_id" {
  value = google_secret_manager_secret.db_password.secret_id
}
