terraform {
  backend "s3" {
    # Replace the account id with the value from the bootstrap output.
    bucket         = "circleguard-tfstate-REPLACE_WITH_ACCOUNT_ID"
    key            = "prod/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "circleguard-tflock"
    encrypt        = true
  }
}
