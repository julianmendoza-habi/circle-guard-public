variable "aws_region" {
  description = "AWS region for shared, account-global resources (ECR)."
  type        = string
  default     = "us-east-1"
}

# ECR repositories are scoped per account+region and shared across all environments
# (dev/stage/prod push the SAME image names with different tags: dev-latest,
# stage-latest, prod-latest). Creating them once here avoids name collisions between
# the per-environment state files.
module "ecr" {
  source = "../modules/ecr"
}

output "ecr_repository_urls" {
  description = "Map of image name -> ECR repository URL. Point CI (Jenkins) at these."
  value       = module.ecr.repository_urls
}
