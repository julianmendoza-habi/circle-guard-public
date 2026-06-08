# Terraform backend bootstrap

Creates the **remote state backend** shared by all environments: an S3 bucket
(versioned + encrypted + private) for state, and a DynamoDB table for state locking.

This root module uses **local state on purpose** — it provisions the backend that
everything else depends on, so it cannot store its own state remotely.

## Run once, first

```bash
cd terraform/bootstrap
terraform init
terraform apply
```

Note the outputs:

```
state_bucket = "circleguard-tfstate-<account_id>"
lock_table   = "circleguard-tflock"
```

Then paste those values into each `terraform/environments/<env>/backend.tf` and run
`terraform init` in that environment.

## Notes

- The bucket has `prevent_destroy = true`. To tear the whole project down, remove
  that line first, then `terraform destroy`.
- Terraform >= 1.10 can lock via an S3 lockfile (`use_lockfile = true`) instead of
  DynamoDB. We keep DynamoDB because it is the broadly-documented, version-agnostic
  approach for this project.
