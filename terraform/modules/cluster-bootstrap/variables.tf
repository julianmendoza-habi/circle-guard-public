variable "app_namespace" {
  description = "Application namespace to create (circleguard-dev | circleguard-stage | circleguard-master)."
  type        = string
}

variable "infra_namespace" {
  description = "Namespace for in-cluster stateful infra (Neo4j, OpenLDAP, optional Kafka)."
  type        = string
  default     = "circleguard-infra"
}

variable "runtime_configmap_name" {
  description = "Name of the runtime ConfigMap consumed by the app Deployments (circleguard-runtime-<env>)."
  type        = string
}

variable "postgres_host" {
  description = "PostgreSQL host the apps should use (RDS endpoint)."
  type        = string
}

variable "redis_host" {
  description = "Redis host the apps should use (ElastiCache primary endpoint)."
  type        = string
}

variable "kafka_bootstrap_servers" {
  description = "Kafka bootstrap servers — MSK brokers, or the in-cluster Kafka DNS when MSK is disabled."
  type        = string
}

variable "neo4j_uri" {
  description = "Neo4j bolt URI (in-cluster service DNS)."
  type        = string
  default     = "bolt://neo4j.circleguard-infra.svc.cluster.local:7687"
}

variable "ldap_primary_url" {
  description = "OpenLDAP URL (in-cluster service DNS)."
  type        = string
  default     = "ldap://openldap.circleguard-infra.svc.cluster.local:389"
}

variable "datasource_password" {
  description = "Database password placed in the shared Secret. In prod, source this from Secrets Manager."
  type        = string
  sensitive   = true
}

variable "jwt_secret" {
  description = "JWT signing secret."
  type        = string
  sensitive   = true
}

variable "qr_secret" {
  description = "QR token signing secret."
  type        = string
  sensitive   = true
}
