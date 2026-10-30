data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
  ecr_base   = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com"
}

# ── ECS Fargate용 Target Group (콘솔에서 생성, target_type = ip) ──────────────
data "aws_lb_target_group" "app" {
  name = "shgtrip-app-tg"
}

data "aws_lb_target_group" "web" {
  name = "shgtrip-web-tg"
}

# 웹(Next.js 서버)이 백엔드를 부르는 내부 ALB. 주소를 하드코딩하면 계정·ALB를 새로 만들 때
# 옛 주소가 남는다(2026-10 계정 이전 때 실제로 옛 계정 ALB를 가리킨 채 배포됐다).
data "aws_lb" "private" {
  name = "shgTrip-private-alb"
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

resource "aws_cloudwatch_log_group" "app" {
  name              = "/shgtrip/app"
  retention_in_days = 30

  tags = { Project = var.project, Env = var.env }
}

resource "aws_cloudwatch_log_group" "web" {
  name              = "/shgtrip/web"
  retention_in_days = 30

  tags = { Project = var.project, Env = var.env }
}

# ── FSQ 추출기 Task Definition ────────────────────────────────────────────────
resource "aws_ecs_task_definition" "fsq_extractor" {
  family                   = "${var.project}-fsq-extractor"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "1024"
  memory                   = "4096"
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

  tags = { Project = var.project, Env = var.env }
}

# ── Spring Boot 배치 Task Definition ─────────────────────────────────────────
resource "aws_ecs_task_definition" "batch" {
  family                   = "${var.project}-batch"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "2048"
  memory                   = "4096"
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
      # GooglePlaceSyncScheduler(@Profile("batch"))가 이미지 업로드 시 정적 URL 생성에 사용
      { name = "CLOUDFRONT_DOMAIN", value = aws_cloudfront_distribution.images.domain_name },
      # 운영 배치는 임베딩 전 LLM enrich(태그·설명·체류시간·입장료)를 항상 수행한다.
      # 로컬은 이 task 정의를 안 쓰므로 기본값 false 유지 → enrich 없이 적재.
      # 대상은 enriched_at IS NULL인 장소라 신규 유입분만 매 실행 보강된다(1회성 아님, 증분).
      { name = "BATCH_ENRICH_ENABLED", value = "true" },
      # TourAPI 관광지 시딩 + 이미지 백필 (Google Places 제거 후 이미지의 유일한 공급원)
      { name = "BATCH_TOURAPI_ENABLED", value = "true" },
      { name = "BATCH_TOURAPI_IMAGE_BACKFILL", value = "true" },
      # Wikidata 인기도(sitelinks). 기본값이 false라 이게 없으면 운영에서 한 번도 돌지 않는다 —
      # 그러면 벡터 검색의 인기도 갈래가 비어 대표 명소를 지켜줄 장치가 없다. 무료·무인증 API.
      { name = "BATCH_WIKIDATA_ENABLED", value = "true" },
    ]

    # 배치 프로필은 spring.config.import 미사용 → 개별 환경변수로 시크릿 주입 필요
    secrets = [
      { name = "DB_HOST", valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:DB_HOST::" },
      { name = "DB_PORT", valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:DB_PORT::" },
      { name = "DB_USERNAME", valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:DB_USERNAME::" },
      { name = "DB_PASSWORD", valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:DB_PASSWORD::" },
      { name = "REDIS_HOST", valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:REDIS_HOST::" },
      { name = "REDIS_PORT", valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:REDIS_PORT::" },
      { name = "ANTHROPIC_API_KEY", valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:ANTHROPIC_API_KEY::" },
      { name = "OPENAI_API_KEY", valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:OPENAI_API_KEY::" },
      { name = "TOUR_API_SERVICE_KEY", valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:TOUR_API_SERVICE_KEY::" },
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

  tags = { Project = var.project, Env = var.env }
}

# ── Backend App Task Definition ───────────────────────────────────────────────
# NOTE: task_definition은 CI/CD(deploy.yml)가 새 이미지로 교체함
#       Terraform은 최초 생성과 Task Role/Execution Role 변경에만 개입
resource "aws_ecs_task_definition" "app" {
  family                   = "${var.project}-app"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.app_task.arn

  container_definitions = jsonencode([{
    name  = "app"
    image = "${local.ecr_base}/shgtrip/app:latest"

    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      { name = "AWS_REGION", value = var.aws_region },
      # 없으면 application.yml 기본값(shgtrip-data — 타인 소유 버킷)을 가리킨다.
      { name = "S3_BUCKET", value = aws_s3_bucket.data.bucket },
      { name = "CLOUDFRONT_DOMAIN", value = aws_cloudfront_distribution.images.domain_name },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.app.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    healthCheck = {
      command     = ["CMD-SHELL", "curl -f http://localhost:8080/actuator/health || exit 1"]
      interval    = 15
      timeout     = 10
      retries     = 2
      startPeriod = 30
    }

    essential = true
  }])

  tags = { Project = var.project, Env = var.env }
}

# ── Frontend Web Task Definition ──────────────────────────────────────────────
resource "aws_ecs_task_definition" "web" {
  family                   = "${var.project}-web"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "1024"
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.web_task.arn

  container_definitions = jsonencode([{
    name  = "web"
    image = "${local.ecr_base}/shgtrip/web:latest"

    portMappings = [{
      containerPort = 3000
      protocol      = "tcp"
    }]

    environment = [
      { name = "NODE_ENV", value = "production" },
      { name = "HOSTNAME", value = "0.0.0.0" },
      # Next.js 서버가 백엔드 API 호출 시 사용하는 내부 ALB 주소
      { name = "BACKEND_URL", value = "http://${data.aws_lb.private.dns_name}:8080" },
    ]

    # NEXT_PUBLIC_* 변수는 빌드타임에 주입됨 (런타임 secrets 아님)
    # SESSION_SECRET 등 런타임 시크릿은 execution role이 가져옴
    secrets = [
      {
        name      = "SESSION_SECRET"
        valueFrom = "${aws_secretsmanager_secret.app_prod.arn}:SESSION_SECRET::"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.web.name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = "ecs"
      }
    }

    # 이미지(node:20-alpine)에 curl이 없어 "curl -f"가 항상 실패했다 — 컨테이너가 떠 있어도 UNHEALTHY로
    # 계속 교체됐다(2026-10 실측 12시간 12회). 헬스체크 하나 때문에 패키지를 넣지 않고 이미지에 반드시 있는
    # node로 검사한다. 쉘 없는 exec 형식이라 slim·distroless로 바꿔도 동작한다.
    # localhost는 IPv6(::1)로 풀릴 수 있는데 Next.js는 IPv4(0.0.0.0)로만 듣기 때문에 127.0.0.1을 쓴다.
    healthCheck = {
      command = ["CMD", "node", "-e",
      "require('http').get('http://127.0.0.1:3000/health', r => process.exit(r.statusCode === 200 ? 0 : 1)).on('error', () => process.exit(1))"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }

    essential = true
  }])

  tags = { Project = var.project, Env = var.env }
}

# ── ECS Service는 콘솔에서 생성 (Terraform 관리 대상 아님) ─────────────────────
# 콘솔에서 생성 시 참고:
# - 클러스터: shgtrip-cluster
# - 서비스명: shgtrip-app / shgtrip-web
# - Task Definition: shgtrip-app / shgtrip-web
# - 서브넷: private-2a, private-2c
# - 보안그룹: shgTrip-app-sg / shgTrip-web-sg
# - Target Group: shgtrip-app-tg / shgtrip-web-tg
# - assign_public_ip: 비활성화
