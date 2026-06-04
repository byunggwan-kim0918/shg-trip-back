# ── ElastiCache Valkey (세션/토큰 캐시) ───────────────────────────────────────
variable "redis_subnet_group_name" {
  description = "ElastiCache 서브넷 그룹명"
  type        = string
}

variable "redis_security_group_ids" {
  description = "ElastiCache에 적용할 보안그룹 ID 목록"
  type        = list(string)
}

resource "aws_elasticache_subnet_group" "main" {
  name       = "shgtrip-valkey-subnet"
  subnet_ids = var.vpc_subnet_ids

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id = "shgtrip-valkey"
  description          = "shgtrip valkey"
  engine               = "valkey"
  engine_version       = "9.0"
  node_type            = "cache.t4g.micro"
  num_cache_clusters   = 1
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = var.redis_security_group_ids

  parameter_group_name = "default.valkey9"

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"

  multi_az_enabled           = false
  automatic_failover_enabled = false
  snapshot_retention_limit   = 1

  log_delivery_configuration {
    destination      = "/elasticache/shgtrip-valkey/slow-logs"
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }

  tags = {
    Project = var.project
    Env     = var.env
  }
}

output "redis_endpoint" {
  description = "Valkey Primary 엔드포인트"
  value       = aws_elasticache_replication_group.main.primary_endpoint_address
  sensitive   = true
}
