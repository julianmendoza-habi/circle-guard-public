environment   = "dev"
app_namespace = "circleguard-dev"

# Single AZ to minimise cost in dev.
azs                  = ["us-east-1a"]
public_subnet_cidrs  = ["10.0.0.0/24"]
private_subnet_cidrs = ["10.0.10.0/24"]
single_nat_gateway   = true

node_instance_types = ["t3.medium"]
node_desired_size   = 2
node_min_size       = 1
node_max_size       = 3
capacity_type       = "SPOT"

rds_instance_class = "db.t4g.micro"
rds_multi_az       = false

redis_node_type          = "cache.t4g.micro"
redis_num_nodes          = 1
redis_automatic_failover = false

# Keep Kafka in-cluster in dev (MSK has no free tier).
enable_msk = false
