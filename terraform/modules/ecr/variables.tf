variable "repository_names" {
  description = "ECR repository names to create (one per microservice image)."
  type        = list(string)
  default = [
    "circleguard/auth-service",
    "circleguard/identity-service",
    "circleguard/form-service",
    "circleguard/promotion-service",
    "circleguard/notification-service",
    "circleguard/dashboard-service",
    "circleguard/file-service",
    "circleguard/gateway-service",
  ]
}

variable "image_tag_mutability" {
  description = "MUTABLE or IMMUTABLE tags."
  type        = string
  default     = "MUTABLE"
}

variable "scan_on_push" {
  description = "Enable image vulnerability scanning on push (feeds the Trivy/security requirement)."
  type        = bool
  default     = true
}

variable "keep_last_images" {
  description = "Lifecycle policy: number of most-recent images to retain per repo."
  type        = number
  default     = 10
}
