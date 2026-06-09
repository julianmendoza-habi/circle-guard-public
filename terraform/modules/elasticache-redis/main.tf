terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-redis"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "this" {
  name        = "${var.name}-redis-sg"
  description = "Allow Redis access from the EKS cluster only."
  vpc_id      = var.vpc_id

  ingress {
    description     = "Redis from EKS"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [var.allowed_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.name}-redis-sg" }
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name}-redis"
  description          = "CircleGuard Redis cache / QR validation store"
  engine               = "redis"
  engine_version       = var.engine_version
  node_type            = var.node_type
  num_cache_clusters   = var.num_cache_clusters
  port                 = 6379

  automatic_failover_enabled = var.automatic_failover
  multi_az_enabled           = var.automatic_failover

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  # The app connects with plain SPRING_DATA_REDIS_HOST/PORT. Keep transit encryption
  # off in non-prod; enable (and switch the client to TLS) for production hardening.
  transit_encryption_enabled = false
  at_rest_encryption_enabled = true

  tags = { Name = "${var.name}-redis" }
}
