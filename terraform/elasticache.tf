# ── ElastiCache Redis (세션/토큰 캐시) ───────────────────────────────────────
variable "redis_subnet_group_name" {
  description = "ElastiCache 서브넷 그룹명"
  type        = string
}

resource "aws_elasticache_subnet_group" "main" {
  name       = "${var.project}-redis-subnet"
  subnet_ids = var.vpc_subnet_ids

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_elasticache_cluster" "main" {
  cluster_id           = "${var.project}-redis"
  engine               = "redis"
  node_type            = "cache.t3.micro"
  num_cache_nodes      = 1
  parameter_group_name = "default.redis7"
  engine_version       = "7.1"
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = var.vpc_security_group_ids

  snapshot_retention_limit = 1
  snapshot_window          = "19:00-20:00"  # UTC

  tags = {
    Project = var.project
    Env     = var.env
  }
}

output "redis_endpoint" {
  description = "Redis 엔드포인트"
  value       = aws_elasticache_cluster.main.cache_nodes[0].address
  sensitive   = true
}
