terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
  }
}

# This module is instantiated with `count = var.enable_msk ? 1 : 0` at the call
# site, so when MSK is disabled (dev/stage) no resources here are created and the
# app falls back to in-cluster Kafka.

resource "aws_security_group" "this" {
  name        = "${var.name}-msk-sg"
  description = "Allow Kafka access from the EKS cluster only."
  vpc_id      = var.vpc_id

  ingress {
    description     = "Kafka plaintext from EKS"
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = [var.allowed_security_group_id]
  }

  ingress {
    description     = "Kafka TLS from EKS"
    from_port       = 9094
    to_port         = 9094
    protocol        = "tcp"
    security_groups = [var.allowed_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.name}-msk-sg" }
}

resource "aws_cloudwatch_log_group" "broker" {
  name              = "/msk/${var.name}"
  retention_in_days = 7
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name}-kafka"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.this.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_ebs_volume_size
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS_PLAINTEXT" # allow both while migrating from in-cluster Kafka
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.broker.name
      }
    }
  }

  tags = { Name = "${var.name}-kafka" }
}
