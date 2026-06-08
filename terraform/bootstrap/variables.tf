variable "aws_region" {
  description = "AWS region that will host the Terraform state backend."
  type        = string
  default     = "us-east-1"
}

variable "state_bucket_name" {
  description = "Globally-unique name for the S3 bucket that stores Terraform state. Account id is appended to keep it unique."
  type        = string
  default     = "circleguard-tfstate"
}

variable "lock_table_name" {
  description = "Name of the DynamoDB table used for Terraform state locking."
  type        = string
  default     = "circleguard-tflock"
}
