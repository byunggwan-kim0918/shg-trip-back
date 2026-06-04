# ── RDS PostgreSQL ─────────────────────────────────────────────────────────────
variable "db_username" {
  description = "RDS 마스터 사용자명"
  type        = string
  default     = "postgres"
}

variable "db_password" {
  description = "RDS 마스터 비밀번호"
  type        = string
  sensitive   = true
}

resource "aws_db_subnet_group" "main" {
  name       = "shgtrip-db-subnet"
  subnet_ids = var.vpc_subnet_ids

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_db_instance" "main" {
  identifier        = "shgtrip-db"
  engine            = "postgres"
  engine_version    = "18.3"
  instance_class    = "db.t3.micro"
  allocated_storage = 20
  storage_type      = "gp2"
  storage_encrypted = true

  db_name  = "trip"
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = ["sg-0be93287071b925cb"]

  parameter_group_name         = "default.postgres18"
  performance_insights_enabled = true

  backup_retention_period = 1
  backup_window           = "18:00-19:00"
  maintenance_window      = "mon:19:00-mon:20:00"

  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "shgtrip-db-final"

  tags = {
    Project = var.project
    Env     = var.env
  }
}

output "rds_endpoint" {
  description = "RDS 엔드포인트"
  value       = aws_db_instance.main.endpoint
  sensitive   = true
}
