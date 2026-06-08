variable "name" {
  description = "Identifier prefix (typically circleguard-<env>)."
  type        = string
}

variable "vpc_id" {
  description = "VPC the MSK cluster lives in."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets for broker placement (length should equal broker count)."
  type        = list(string)
}

variable "allowed_security_group_id" {
  description = "Security group allowed to reach the brokers (the EKS cluster SG)."
  type        = string
}

variable "kafka_version" {
  description = "Apache Kafka version (3.6.x aligns with the Confluent 7.6 line used in-cluster)."
  type        = string
  default     = "3.6.0"
}

variable "broker_instance_type" {
  description = "MSK broker instance type."
  type        = string
  default     = "kafka.t3.small"
}

variable "broker_count" {
  description = "Number of broker nodes (must be a multiple of the number of subnets)."
  type        = number
  default     = 2
}

variable "broker_ebs_volume_size" {
  description = "EBS volume size per broker in GB."
  type        = number
  default     = 10
}
