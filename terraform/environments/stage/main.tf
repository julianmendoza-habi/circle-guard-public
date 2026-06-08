locals {
  name         = "circleguard-${var.environment}"
  cluster_name = "circleguard-${var.environment}"
}

module "network" {
  source = "../../modules/network"

  name                 = local.name
  cluster_name         = local.cluster_name
  vpc_cidr             = var.vpc_cidr
  azs                  = var.azs
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  single_nat_gateway   = var.single_nat_gateway
}

module "eks" {
  source = "../../modules/eks"

  cluster_name        = local.cluster_name
  cluster_version     = var.cluster_version
  private_subnet_ids  = module.network.private_subnet_ids
  public_subnet_ids   = module.network.public_subnet_ids
  node_instance_types = var.node_instance_types
  node_desired_size   = var.node_desired_size
  node_min_size       = var.node_min_size
  node_max_size       = var.node_max_size
  capacity_type       = var.capacity_type
}

module "rds" {
  source = "../../modules/rds-postgres"

  name                      = local.name
  vpc_id                    = module.network.vpc_id
  private_subnet_ids        = module.network.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  instance_class            = var.rds_instance_class
  multi_az                  = var.rds_multi_az
}

module "redis" {
  source = "../../modules/elasticache-redis"

  name                      = local.name
  vpc_id                    = module.network.vpc_id
  private_subnet_ids        = module.network.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  node_type                 = var.redis_node_type
  num_cache_clusters        = var.redis_num_nodes
  automatic_failover        = var.redis_automatic_failover
}

module "msk" {
  source = "../../modules/msk-kafka"
  count  = var.enable_msk ? 1 : 0

  name                      = local.name
  vpc_id                    = module.network.vpc_id
  private_subnet_ids        = module.network.private_subnet_ids
  allowed_security_group_id = module.eks.cluster_security_group_id
  broker_instance_type      = var.msk_broker_instance_type
  broker_count              = var.msk_broker_count
}

module "cluster_bootstrap" {
  source = "../../modules/cluster-bootstrap"

  app_namespace          = var.app_namespace
  runtime_configmap_name = "circleguard-runtime-${var.environment}"
  postgres_host          = module.rds.endpoint
  redis_host             = module.redis.primary_endpoint
  # kafka_bootstrap depends on the infra namespace this module also creates; the
  # literal DNS is fixed, so reference the namespace var directly to avoid a cycle.
  kafka_bootstrap_servers = var.enable_msk ? module.msk[0].bootstrap_brokers : "kafka.circleguard-infra.svc.cluster.local:9092"

  datasource_password = var.datasource_password
  jwt_secret          = var.jwt_secret
  qr_secret           = var.qr_secret
}
