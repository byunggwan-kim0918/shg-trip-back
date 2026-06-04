data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
}

# ── ECS 클러스터 ──────────────────────────────────────────────────────────────
resource "aws_ecs_cluster" "main" {
  name = "${var.project}-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = {
    Project = var.project
    Env     = var.env
  }
}

# ── CloudWatch Log Groups ─────────────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "fsq_extractor" {
  name              = "/ecs/${var.project}/fsq-extractor"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "batch" {
  name              = "/ecs/${var.project}/batch"
  retention_in_days = 30
}

# ── FSQ 추출기 Task Definition ────────────────────────────────────────────────
resource "aws_ecs_task_definition" "fsq_extractor" {
  family                   = "${var.project}-fsq-extractor"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "1024" # 1 vCPU
  memory                   = "2048" # 2 GB
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.fsq_extractor_task.arn

  container_definitions = jsonencode([{
    name  = "fsq-extractor"
    image = "${aws_ecr_repository.fsq_extractor.repository_url}:latest"

    environment = [
      { name = "S3_BUCKET", value = aws_s3_bucket.data.bucket },
      { name = "S3_PREFIX", value = "foursquare" },
      { name = "AWS_REGION", value = var.aws_region },
    ]

    secrets = [{
      name      = "FSQ_ACCESS_TOKEN"
      valueFrom = aws_secretsmanager_secret.fsq_token.arn
    }]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.fsq_extractor.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    essential = true
  }])

  tags = {
    Project = var.project
    Env     = var.env
  }
}

# ── Spring Boot 배치 Task Definition ─────────────────────────────────────────
# NOTE: 환경변수(DB, API 키 등)는 실제 값으로 교체 필요
resource "aws_ecs_task_definition" "batch" {
  family                   = "${var.project}-batch"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "2048" # 2 vCPU (임베딩 생성 고려)
  memory                   = "4096" # 4 GB
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.batch_task.arn

  container_definitions = jsonencode([{
    name  = "batch"
    image = "${aws_ecr_repository.batch.repository_url}:latest"

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "batch" },
      { name = "BATCH_FOURSQUARE_SOURCE", value = "s3" },
      { name = "BATCH_FOURSQUARE_S3_BUCKET", value = aws_s3_bucket.data.bucket },
      { name = "AWS_REGION", value = var.aws_region },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.batch.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    essential = true
  }])

  tags = {
    Project = var.project
    Env     = var.env
  }
}
