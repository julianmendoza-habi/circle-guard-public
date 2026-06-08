terraform {
  backend "s3" {
    # Fill bucket from the bootstrap output (circleguard-tfstate-<account_id>).
    bucket         = "circleguard-tfstate-REPLACE_WITH_ACCOUNT_ID"
    key            = "shared/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "circleguard-tflock"
    encrypt        = true
  }
}
