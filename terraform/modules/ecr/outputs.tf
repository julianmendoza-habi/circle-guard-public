output "repository_urls" {
  description = "Map of repository name -> repository URL (push targets for CI)."
  value       = { for name, repo in aws_ecr_repository.this : name => repo.repository_url }
}

output "registry_id" {
  description = "ECR registry account id."
  value       = values(aws_ecr_repository.this)[0].registry_id
}
