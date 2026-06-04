# ── 앱 통합 Secret (application-prod.yml의 aws-secretsmanager:shg-trip/prod) ──
# Spring Cloud AWS가 이 시크릿 하나를 읽어서 모든 ${변수}를 주입한다.

variable "app_secrets" {
  description = "shg-trip/prod Secrets Manager에 저장할 키-값 쌍"
  type        = map(string)
  sensitive   = true
  # terraform.tfvars에서 입력:
  # app_secrets = {
  #   DB_HOST              = "shgtrip-postgres.xxxx.ap-northeast-2.rds.amazonaws.com"
  #   DB_PORT              = "5432"
  #   DB_USERNAME          = "weShg"
  #   DB_PASSWORD          = "..."
  #   REDIS_HOST           = "shgtrip-redis.xxxx.cache.amazonaws.com"
  #   REDIS_PORT           = "6379"
  #   JWT_SECRET           = "..."
  #   KAKAO_CLIENT_ID      = "..."
  #   KAKAO_CLIENT_SECRET  = "..."
  #   GOOGLE_CLIENT_ID     = "..."
  #   GOOGLE_CLIENT_SECRET = "..."
  #   NAVER_CLIENT_ID      = "..."
  #   NAVER_CLIENT_SECRET  = "..."
  #   ANTHROPIC_API_KEY    = "..."
  #   OPENAI_API_KEY       = "..."
  #   GOOGLE_PLACES_API_KEY= "..."
  # }
}

resource "aws_secretsmanager_secret" "app_prod" {
  name                    = "shg-trip/prod"
  description             = "shg-trip 앱 prod 환경 전체 시크릿 (Spring Cloud AWS 통합)"
  recovery_window_in_days = 7

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_secretsmanager_secret_version" "app_prod" {
  secret_id     = aws_secretsmanager_secret.app_prod.id
  secret_string = jsonencode(var.app_secrets)
}

# ── ECS Task Execution Role에 앱 시크릿 읽기 권한 추가 ────────────────────────
resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name = "${var.project}-ecs-execution-secrets-policy"
  role = aws_iam_role.ecs_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = ["secretsmanager:GetSecretValue"]
      Resource = [
        aws_secretsmanager_secret.app_prod.arn,
        aws_secretsmanager_secret.fsq_token.arn,
      ]
    }]
  })
}

output "app_secret_arn" {
  description = "앱 통합 시크릿 ARN"
  value       = aws_secretsmanager_secret.app_prod.arn
}
