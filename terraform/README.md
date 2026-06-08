# CircleGuard — Infrastructure as Code (Terraform / AWS)

Provisions the AWS platform that runs the CircleGuard microservices on **EKS**, with
backing data services as **managed AWS services** (RDS PostgreSQL, ElastiCache Redis,
optional MSK Kafka) and the services with no managed equivalent (**Neo4j**, **OpenLDAP**)
running in-cluster. The existing `deploy/k8s/**` manifests deploy unchanged onto the
provisioned cluster — Terraform only renders the runtime ConfigMap/Secret that points the
apps at the managed endpoints.

## Requirements satisfied

| Requirement                          | Where |
|--------------------------------------|-------|
| Full IaC with Terraform              | this whole `terraform/` tree |
| Modular structure                    | `modules/` (network, eks, rds-postgres, elasticache-redis, msk-kafka, ecr, cluster-bootstrap) |
| Multiple environments (dev/stage/prod) | `environments/{dev,stage,prod}/`, directory-per-env |
| Remote backend for state             | S3 + DynamoDB, created by `bootstrap/`, used by every env |
| Architecture diagrams                | [docs/architecture.md](./docs/architecture.md) |

## Layout

```
terraform/
├── bootstrap/          # LOCAL state — creates the S3 bucket + DynamoDB lock table
├── shared/             # account/region-global resources (ECR repos), remote state key shared/
├── modules/            # reusable building blocks
│   ├── network/              elasticache-redis/
│   ├── eks/                  msk-kafka/
│   ├── rds-postgres/         ecr/
│   └── cluster-bootstrap/    (namespaces + runtime ConfigMap/Secret wiring)
├── environments/
│   ├── dev/   stage/   prod/   # each: backend.tf + providers.tf + main.tf + variables.tf + outputs.tf + terraform.tfvars
└── docs/architecture.md
```

## Why directory-per-environment (not workspaces)

Each environment is its own root module with its own `backend.tf` (a distinct S3 key) and
`terraform.tfvars`, all calling the same `modules/`. This gives **isolated state**, makes it
impossible to apply dev variables against prod, and produces clean, reviewable diffs. The
trade-off is a small amount of duplicated wiring in each env's `main.tf` — acceptable, and
far safer for a graded, demoable project than hiding environment differences behind
`terraform.workspace` conditionals on a single backend key.

> Note on naming: the production environment lives in `environments/prod/` but its
> `environment` value is **`master`**, matching the repo's existing production namespace
> (`circleguard-master`) and ConfigMap (`circleguard-runtime-master`). This keeps the
> Terraform-rendered resources aligned with `deploy/k8s/apps/master`.

## Apply order

### 1. Bootstrap the remote backend (once)

```bash
cd terraform/bootstrap
terraform init
terraform apply
# note: state_bucket = circleguard-tfstate-<account_id>, lock_table = circleguard-tflock
```

Replace `REPLACE_WITH_ACCOUNT_ID` in every `backend.tf`
(`shared/`, `environments/dev`, `stage`, `prod`) with your account id.

### 2. Shared resources (ECR)

```bash
cd terraform/shared
terraform init
terraform apply          # creates 8 ECR repos, output: ecr_repository_urls
```

### 3. An environment

```bash
cd terraform/environments/dev
terraform init
terraform plan
terraform apply
```

Provisioning order within an env (Terraform resolves automatically): network → eks →
rds / redis / (msk) → cluster-bootstrap (namespaces + runtime ConfigMap/Secret).

### 4. Deploy the apps + in-cluster infra

```bash
aws eks update-kubeconfig --name circleguard-dev --region us-east-1

# In-cluster stateful infra that has no managed AWS equivalent:
kubectl apply -f deploy/k8s/infra/postgres-redis-neo4j.yaml   # use only the Neo4j parts in AWS
kubectl apply -f deploy/k8s/infra/openldap.yaml
# (Kafka/Zookeeper only when enable_msk = false)

# Create the per-service logical databases on RDS (reuses the existing idempotent Job,
# pointed at POSTGRES_HOST from the Terraform-rendered ConfigMap):
kubectl apply -f deploy/k8s/infra/postgres-ensure-databases.yaml

# The application Deployments (image tags pushed to ECR by CI):
kubectl apply -f deploy/k8s/apps/dev/microservices.yaml
kubectl -n circleguard-dev get pods
```

### 5. Tear down (cost control)

```bash
cd terraform/environments/dev && terraform destroy
```

## How apps reach managed services (the one seam)

The app Deployments read `POSTGRES_HOST`, `REDIS_HOST`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`,
`NEO4J_URI`, `LDAP_PRIMARY_URL` from the `circleguard-runtime-<env>` ConfigMap, which the
`cluster-bootstrap` module renders with the **managed-service endpoints** (RDS, ElastiCache,
MSK). The JDBC URL is built from `$(POSTGRES_HOST)` — note `POSTGRES_HOST` is declared
explicitly in each container's `env:` list (Kubernetes `$()` expansion does not see
`envFrom` variables). No application code changes are required to move from in-cluster
Postgres/Redis/Kafka to the managed services.

## Cost notes

- **EKS control plane**: ~$0.10/hr (~$73/mo) per cluster — unavoidable while a cluster
  exists. Cheaper alternative (not used here, because the brief asks for separate
  environments): one shared cluster with a namespace per env.
- **NAT gateway**: ~$32/mo each + data. `single_nat_gateway = true` in dev/stage; one per
  AZ only in prod. `terraform destroy` dev when idle.
- **MSK**: no free tier → `enable_msk = false` in dev/stage (in-cluster Kafka); `true` in prod.
- **RDS / ElastiCache** `t4g.micro`: near/within free tier.
- **Spot** node capacity in dev/stage; on-demand in prod.

See the per-environment sizing in each `terraform.tfvars` and the full matrix in
[docs/architecture.md](./docs/architecture.md).

## Validation

`terraform` is not installed in the authoring environment, so run these where it is
available (locally or in CI):

```bash
terraform fmt -recursive -check
# per root module:
terraform -chdir=bootstrap init -backend=false && terraform -chdir=bootstrap validate
terraform -chdir=environments/dev init -backend=false && terraform -chdir=environments/dev validate
```
