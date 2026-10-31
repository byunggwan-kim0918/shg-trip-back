# ── RDS PostgreSQL ─────────────────────────────────────────────────────────────
variable "db_username" {
  description = "RDS 마스터 사용자명"
  type        = string
  default     = "postgres"
}

# DB를 처음 만들 때만 -var로 넘긴다. 이후 변경은 ignore_changes로 Terraform이 건드리지 않는다.
# 바꿀 때는 RDS와 shg-trip/prod의 DB_PASSWORD를 같이 바꾼다 — 앱이 마스터 계정으로 붙는다
variable "db_password" {
  description = "RDS 마스터 비밀번호 (최초 생성 때만)"
  type        = string
  sensitive   = true
  default     = null
}

resource "aws_db_subnet_group" "main" {
  name       = "shgtrip-db-subnet"
  subnet_ids = local.private_subnet_ids

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_db_instance" "main" {
  identifier        = "shgtrip-db"
  engine            = "postgres"
  engine_version    = "18"
  instance_class    = "db.t3.micro"
  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "trip"
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]

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

  lifecycle {
    prevent_destroy = true
    ignore_changes  = [password]
  }
}

output "rds_endpoint" {
  description = "RDS 엔드포인트"
  value       = aws_db_instance.main.endpoint
  sensitive   = true
}
