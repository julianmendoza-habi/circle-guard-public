# The repo's production environment is named "master" (see deploy/k8s/apps/master):
# namespace circleguard-master, ConfigMap circleguard-runtime-master. We keep that
# identity so Terraform-created resources match the existing production manifests,
# while this directory stays "prod" to satisfy the dev/stage/prod convention.
environment   = "master"
app_namespace = "circleguard-master"

# Three AZs and one NAT per AZ for high availability.
azs                  = ["us-east-1a", "us-east-1b", "us-east-1c"]
public_subnet_cidrs  = ["10.0.0.0/24", "10.0.1.0/24", "10.0.2.0/24"]
private_subnet_cidrs = ["10.0.10.0/24", "10.0.11.0/24", "10.0.12.0/24"]
single_nat_gateway   = false

node_instance_types = ["t3.large"]
node_desired_size   = 3
node_min_size       = 3
node_max_size       = 6
capacity_type       = "ON_DEMAND"

rds_instance_class = "db.t4g.small"
rds_multi_az       = true

redis_node_type          = "cache.t4g.small"
redis_num_nodes          = 2
redis_automatic_failover = true

# Managed Kafka in production.
enable_msk               = true
msk_broker_instance_type = "kafka.t3.small"
msk_broker_count         = 3

# IMPORTANT: do NOT commit real production secrets here. Override at apply time:
#   TF_VAR_datasource_password=... TF_VAR_jwt_secret=... TF_VAR_qr_secret=... terraform apply
# or wire these from the RDS Secrets Manager secret created by the rds module.
