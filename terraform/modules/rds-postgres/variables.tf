variable "name" {
  description = "Identifier prefix (typically circleguard-<env>)."
  type        = string
}

variable "vpc_id" {
  description = "VPC the database lives in."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets for the DB subnet group."
  type        = list(string)
}

variable "allowed_security_group_id" {
  description = "Security group allowed to reach Postgres on 5432 (the EKS cluster SG)."
  type        = string
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "allocated_storage" {
  description = "Allocated storage in GB."
  type        = number
  default     = 20
}

variable "engine_version" {
  description = "PostgreSQL engine version."
  type        = string
  default     = "16.4"
}

variable "multi_az" {
  description = "Enable Multi-AZ standby (prod)."
  type        = bool
  default     = false
}

variable "master_username" {
  description = "Master username."
  type        = string
  default     = "circleguard"
}

variable "skip_final_snapshot" {
  description = "Skip the final snapshot on destroy (true for dev/stage)."
  type        = bool
  default     = true
}
