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
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject", "s3:ListBucket"]
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
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:ListBucket", "s3:PutObject"]
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
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
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
