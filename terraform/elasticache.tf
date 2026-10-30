# ── ElastiCache Valkey (세션/토큰 캐시) ───────────────────────────────────────
resource "aws_elasticache_subnet_group" "main" {
  name       = "shgtrip-valkey-subnet"
  subnet_ids = local.private_subnet_ids

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_elasticache_replication_group" "main" {
  replication_group_id = "shgtrip-valkey"
  description          = "shgtrip valkey"
  engine               = "valkey"
  # Valkey는 <major>.<minor> 형식이 필수라 마이너까지 고정해야 한다 (RDS처럼 메이저만 쓸 수 없음).
  # 폐기되면 describe-cache-engine-versions로 확인 후 올릴 것.
  engine_version     = "9.0"
  node_type          = "cache.t4g.micro"
  num_cache_clusters = 1
  port               = 6379

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.valkey.id]

  parameter_group_name = "default.valkey9"

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  transit_encryption_mode    = "required"

  multi_az_enabled           = false
  automatic_failover_enabled = false
  snapshot_retention_limit   = 1

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.valkey_slow_log.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }

  tags = {
    Project = var.project
    Env     = var.env
  }
  lifecycle {
    prevent_destroy = true
  }
}

# 로그그룹 없이 설정된 전송은 오류로 멈춘 채 재시도하지 않는다 — 나중에 만들었으면 modify-replication-group으로 다시 켠다
resource "aws_cloudwatch_log_group" "valkey_slow_log" {
  name              = "/elasticache/shgtrip-valkey/slow-logs"
  retention_in_days = 30

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
