variable "name" {
  description = "Name prefix for network resources (typically circleguard-<env>)."
  type        = string
}

variable "cluster_name" {
  description = "EKS cluster name, used to tag subnets for Kubernetes load-balancer discovery."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.0.0.0/16"
}

variable "azs" {
  description = "Availability zones to spread subnets across. One public + one private subnet per AZ."
  type        = list(string)
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for the public subnets (one per AZ). Length must match azs."
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for the private subnets (one per AZ). Length must match azs."
  type        = list(string)
}

variable "single_nat_gateway" {
  description = "If true, route all private subnets through ONE NAT gateway (cheaper; dev/stage). If false, one NAT per AZ (HA; prod)."
  type        = bool
  default     = true
}
