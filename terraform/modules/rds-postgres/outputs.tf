output "endpoint" {
  description = "Hostname of the RDS instance (feeds POSTGRES_HOST in the cluster ConfigMap)."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "Postgres port."
  value       = aws_db_instance.this.port
}

output "master_username" {
  description = "Master username."
  value       = var.master_username
}

output "master_password" {
  description = "Master password (sensitive)."
  value       = random_password.master.result
  sensitive   = true
}

output "secret_arn" {
  description = "ARN of the Secrets Manager secret holding DB credentials."
  value       = aws_secretsmanager_secret.db.arn
}
