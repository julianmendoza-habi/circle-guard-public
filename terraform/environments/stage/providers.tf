provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "circleguard"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# The Kubernetes provider is configured from the EKS module outputs so the
# cluster-bootstrap resources (namespaces, ConfigMap, Secret) land on the cluster
# this same configuration just created.
data "aws_eks_cluster_auth" "this" {
  name = module.eks.cluster_name
}

provider "kubernetes" {
  host                   = module.eks.cluster_endpoint
  cluster_ca_certificate = base64decode(module.eks.cluster_ca_certificate)
  token                  = data.aws_eks_cluster_auth.this.token
}
