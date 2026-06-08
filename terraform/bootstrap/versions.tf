terraform {
  required_version = ">= 1.7.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
  # NOTE: bootstrap intentionally uses LOCAL state. It creates the S3 bucket and
  # DynamoDB table that every other root module uses as its remote backend, so it
  # cannot itself depend on that backend existing (chicken-and-egg).
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "circleguard"
      Component = "tf-backend"
      ManagedBy = "terraform"
    }
  }
}
