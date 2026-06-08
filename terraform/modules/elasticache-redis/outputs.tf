output "primary_endpoint" {
  description = "Primary endpoint hostname (feeds REDIS_HOST in the cluster ConfigMap)."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  description = "Redis port."
  value       = aws_elasticache_replication_group.this.port
}
