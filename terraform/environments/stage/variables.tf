variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "environment" {
  description = "Environment short name (dev | stage | prod)."
  type        = string
}

variable "app_namespace" {
  description = "Kubernetes namespace for the app (circleguard-dev | circleguard-stage | circleguard-master)."
  type        = string
}

# --- Network ---
variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}
variable "azs" {
  type = list(string)
}
variable "public_subnet_cidrs" {
  type = list(string)
}
variable "private_subnet_cidrs" {
  type = list(string)
}
variable "single_nat_gateway" {
  type    = bool
  default = true
}

# --- EKS ---
variable "cluster_version" {
  type    = string
  default = "1.30"
}
variable "node_instance_types" {
  type    = list(string)
  default = ["t3.medium"]
}
variable "node_desired_size" {
  type    = number
  default = 2
}
variable "node_min_size" {
  type    = number
  default = 1
}
variable "node_max_size" {
  type    = number
  default = 3
}
variable "capacity_type" {
  type    = string
  default = "SPOT"
}

# --- RDS ---
variable "rds_instance_class" {
  type    = string
  default = "db.t4g.micro"
}
variable "rds_multi_az" {
  type    = bool
  default = false
}

# --- ElastiCache ---
variable "redis_node_type" {
  type    = string
  default = "cache.t4g.micro"
}
variable "redis_num_nodes" {
  type    = number
  default = 1
}
variable "redis_automatic_failover" {
  type    = bool
  default = false
}

# --- MSK (optional) ---
variable "enable_msk" {
  type    = bool
  default = false
}
variable "msk_broker_instance_type" {
  type    = string
  default = "kafka.t3.small"
}
variable "msk_broker_count" {
  type    = number
  default = 2
}

# --- App secrets (override per environment; prod should source from Secrets Manager) ---
variable "datasource_password" {
  type      = string
  sensitive = true
  default   = "password"
}
variable "jwt_secret" {
  type      = string
  sensitive = true
  default   = "my-super-secret-dev-key-32-chars-long-12345678"
}
variable "qr_secret" {
  type      = string
  sensitive = true
  default   = "my-qr-secret-key-for-dev-1234567890"
}
