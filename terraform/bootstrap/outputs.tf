output "state_bucket" {
  description = "Name of the S3 bucket holding Terraform state. Paste into each environment's backend.tf."
  value       = aws_s3_bucket.tfstate.id
}

output "lock_table" {
  description = "Name of the DynamoDB lock table. Paste into each environment's backend.tf."
  value       = aws_dynamodb_table.tflock.name
}

output "region" {
  description = "Region the backend lives in."
  value       = var.aws_region
}
