# ── 앱 통합 Secret (application-prod.yml의 aws-secretsmanager:shg-trip/prod) ──
# Spring Cloud AWS가 이 시크릿 하나를 읽어서 모든 ${변수}를 주입한다.
# 값은 Terraform 밖에서 넣는다(terraform/scripts/set-secret.sh) — tfvars·state에 평문이 남지 않게

resource "aws_secretsmanager_secret" "app_prod" {
  name                    = "shg-trip/prod"
  description             = "shg-trip 앱 prod 환경 전체 시크릿 (Spring Cloud AWS 통합)"
  recovery_window_in_days = 7

  tags = {
    Project = var.project
    Env     = var.env
  }
}

removed {
  from = aws_secretsmanager_secret_version.app_prod

  lifecycle {
    destroy = false
  }
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
