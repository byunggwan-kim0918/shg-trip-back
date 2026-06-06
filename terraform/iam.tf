# ── ECS Task 공통 Assume Role ─────────────────────────────────────────────────
data "aws_iam_policy_document" "ecs_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# ── FSQ 추출기 Task Role ──────────────────────────────────────────────────────
resource "aws_iam_role" "fsq_extractor_task" {
  name               = "${var.project}-fsq-extractor-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_iam_role_policy" "fsq_extractor_task" {
  name = "${var.project}-fsq-extractor-task-policy"
  role = aws_iam_role.fsq_extractor_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # S3 CSV 업로드
        Effect = "Allow"
        Action = ["s3:PutObject", "s3:GetObject", "s3:ListBucket"]
        Resource = [
          aws_s3_bucket.data.arn,
          "${aws_s3_bucket.data.arn}/foursquare/*"
        ]
      },
      {
        # FSQ 토큰 읽기
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [aws_secretsmanager_secret.fsq_token.arn]
      }
    ]
  })
}

# ── Spring Boot 배치 Task Role ────────────────────────────────────────────────
resource "aws_iam_role" "batch_task" {
  name               = "${var.project}-batch-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_iam_role_policy" "batch_task" {
  name = "${var.project}-batch-task-policy"
  role = aws_iam_role.batch_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # S3 CSV 읽기 + 이미지 업로드
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:ListBucket", "s3:PutObject"]
        Resource = [
          aws_s3_bucket.data.arn,
          "${aws_s3_bucket.data.arn}/*"
        ]
      }
    ]
  })
}

# ── ECS Task Execution Role (공통 — ECR pull, CloudWatch Logs) ────────────────
resource "aws_iam_role" "ecs_execution" {
  name               = "${var.project}-ecs-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# ── Step Functions Execution Role ─────────────────────────────────────────────
data "aws_iam_policy_document" "sfn_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["states.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "sfn_pipeline" {
  name               = "${var.project}-sfn-pipeline-role"
  assume_role_policy = data.aws_iam_policy_document.sfn_assume_role.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_iam_role_policy" "sfn_pipeline" {
  name = "${var.project}-sfn-pipeline-policy"
  role = aws_iam_role.sfn_pipeline.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecs:RunTask", "ecs:StopTask", "ecs:DescribeTasks"]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          aws_iam_role.ecs_execution.arn,
          aws_iam_role.fsq_extractor_task.arn,
          aws_iam_role.batch_task.arn,
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["sns:Publish"]
        Resource = [aws_sns_topic.pipeline_alerts.arn]
      }
    ]
  })
}

# ── EventBridge Scheduler Role ────────────────────────────────────────────────
data "aws_iam_policy_document" "scheduler_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "scheduler" {
  name               = "${var.project}-scheduler-role"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume_role.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_iam_role_policy" "scheduler" {
  name = "${var.project}-scheduler-policy"
  role = aws_iam_role.scheduler.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["states:StartExecution"]
      Resource = [aws_sfn_state_machine.monthly_pipeline.arn]
    }]
  })
}

# ── App Service Task Role ─────────────────────────────────────────────────────
# Task Role = 컨테이너 프로세스가 AWS SDK로 직접 접근할 때 사용
# Spring Cloud AWS는 런타임에 SDK로 Secrets Manager를 호출 → task role 필요
resource "aws_iam_role" "app_task" {
  name               = "${var.project}-app-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = { Project = var.project, Env = var.env }
}

resource "aws_iam_role_policy" "app_task" {
  name = "${var.project}-app-task-policy"
  role = aws_iam_role.app_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Spring Cloud AWS: 앱 프로세스가 런타임에 직접 시크릿 조회
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = [aws_secretsmanager_secret.app_prod.arn]
      },
      {
        # S3: 장소 이미지 저장/조회
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = ["${aws_s3_bucket.data.arn}/*"]
      },
      {
        # ECS Exec (운영 중 디버깅용 — 필요 없으면 제거 가능)
        Effect   = "Allow"
        Action   = [
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel"
        ]
        Resource = "*"
      }
    ]
  })
}

# ── Web Service Task Role ─────────────────────────────────────────────────────
resource "aws_iam_role" "web_task" {
  name               = "${var.project}-web-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume_role.json

  tags = { Project = var.project, Env = var.env }
}

# Web Task Role은 현재 별도 AWS 리소스 직접 접근 없음
# SESSION_SECRET은 execution role이 secrets manager에서 가져와 환경변수로 주입
