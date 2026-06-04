# ── RDS PostgreSQL (pgvector) ─────────────────────────────────────────────────
variable "db_username" {
  description = "RDS 마스터 사용자명"
  type        = string
  default     = "weShg"
}

variable "db_password" {
  description = "RDS 마스터 비밀번호"
  type        = string
  sensitive   = true
}

variable "db_subnet_group_name" {
  description = "RDS 서브넷 그룹명 (기존 VPC에 맞게 입력)"
  type        = string
}

resource "aws_db_instance" "main" {
  identifier        = "${var.project}-postgres"
  engine            = "postgres"
  engine_version    = "15.7"
  instance_class    = "db.t3.medium"
  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "trip"
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = var.db_subnet_group_name
  vpc_security_group_ids = var.vpc_security_group_ids

  # pgvector 활성화
  parameter_group_name = aws_db_parameter_group.postgres15.name

  backup_retention_period = 7
  backup_window           = "18:00-19:00" # UTC (= 03:00 KST)
  maintenance_window      = "Mon:19:00-Mon:20:00"

  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.project}-postgres-final"

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_db_parameter_group" "postgres15" {
  name   = "${var.project}-postgres15"
  family = "postgres15"

  # pgvector 공유 라이브러리 로드
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }

  tags = {
    Project = var.project
    Env     = var.env
  }
}

# RDS 엔드포인트 출력
output "rds_endpoint" {
  description = "RDS 엔드포인트"
  value       = aws_db_instance.main.endpoint
  sensitive   = true
}
