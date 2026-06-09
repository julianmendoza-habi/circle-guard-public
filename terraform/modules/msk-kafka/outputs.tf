output "bootstrap_brokers" {
  description = "Plaintext bootstrap broker connection string (feeds SPRING_KAFKA_BOOTSTRAP_SERVERS)."
  value       = aws_msk_cluster.this.bootstrap_brokers
}

output "bootstrap_brokers_tls" {
  description = "TLS bootstrap broker connection string."
  value       = aws_msk_cluster.this.bootstrap_brokers_tls
}
