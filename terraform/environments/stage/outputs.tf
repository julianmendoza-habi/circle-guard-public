output "cluster_name" {
  description = "EKS cluster name (use with: aws eks update-kubeconfig --name <this>)."
  value       = module.eks.cluster_name
}

output "cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "postgres_endpoint" {
  description = "RDS endpoint injected into the cluster ConfigMap as POSTGRES_HOST."
  value       = module.rds.endpoint
}

output "redis_endpoint" {
  description = "ElastiCache endpoint injected as REDIS_HOST."
  value       = module.redis.primary_endpoint
}

output "kafka_bootstrap_servers" {
  description = "Kafka bootstrap servers in use (MSK or in-cluster)."
  value       = var.enable_msk ? module.msk[0].bootstrap_brokers : "kafka.circleguard-infra.svc.cluster.local:9092"
}

output "app_namespace" {
  value = module.cluster_bootstrap.app_namespace
}
