variable "name" {
  description = "Identifier prefix (typically circleguard-<env>)."
  type        = string
}

variable "vpc_id" {
  description = "VPC the cache lives in."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets for the cache subnet group."
  type        = list(string)
}

variable "allowed_security_group_id" {
  description = "Security group allowed to reach Redis on 6379 (the EKS cluster SG)."
  type        = string
}

variable "node_type" {
  description = "ElastiCache node type."
  type        = string
  default     = "cache.t4g.micro"
}

variable "engine_version" {
  description = "Redis engine version."
  type        = string
  default     = "7.1"
}

variable "num_cache_clusters" {
  description = "Number of nodes in the replication group (1 = no replica; 2+ enables failover)."
  type        = number
  default     = 1
}

variable "automatic_failover" {
  description = "Enable automatic failover (requires num_cache_clusters >= 2; prod)."
  type        = bool
  default     = false
}
