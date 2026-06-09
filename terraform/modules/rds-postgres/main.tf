terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.60"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-pg"
  subnet_ids = var.private_subnet_ids
  tags       = { Name = "${var.name}-pg-subnets" }
}

resource "aws_security_group" "this" {
  name        = "${var.name}-pg-sg"
  description = "Allow Postgres access from the EKS cluster only."
  vpc_id      = var.vpc_id

  ingress {
    description     = "Postgres from EKS"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [var.allowed_security_group_id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.name}-pg-sg" }
}

resource "random_password" "master" {
  length  = 24
  special = false # avoid characters that need escaping in JDBC URLs / secrets
}

resource "aws_db_instance" "this" {
  identifier        = "${var.name}-pg"
  engine            = "postgres"
  engine_version    = var.engine_version
  instance_class    = var.instance_class
  allocated_storage = var.allocated_storage
  storage_encrypted = true

  db_name  = "circleguard" # bootstrap db; service-specific DBs are created post-provision
  username = var.master_username
  password = random_password.master.result

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  multi_az               = var.multi_az
  publicly_accessible    = false

  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${var.name}-pg-final"
  deletion_protection       = false

  tags = { Name = "${var.name}-pg" }
}

# Store credentials in Secrets Manager rather than in state/outputs in plaintext.
resource "aws_secretsmanager_secret" "db" {
  name = "${var.name}/postgres/master"
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode({
    username = var.master_username
    password = random_password.master.result
    host     = aws_db_instance.this.address
    port     = aws_db_instance.this.port
  })
}
