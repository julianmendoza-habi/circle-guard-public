environment   = "stage"
app_namespace = "circleguard-stage"

# Two AZs for a more production-like topology.
azs                  = ["us-east-1a", "us-east-1b"]
public_subnet_cidrs  = ["10.0.0.0/24", "10.0.1.0/24"]
private_subnet_cidrs = ["10.0.10.0/24", "10.0.11.0/24"]
single_nat_gateway   = true

node_instance_types = ["t3.medium"]
node_desired_size   = 2
node_min_size       = 2
node_max_size       = 4
capacity_type       = "SPOT"

rds_instance_class = "db.t4g.micro"
rds_multi_az       = false

redis_node_type          = "cache.t4g.micro"
redis_num_nodes          = 1
redis_automatic_failover = false

enable_msk = false
