# ── SNS 알림 토픽 ─────────────────────────────────────────────────────────────
resource "aws_sns_topic" "pipeline_alerts" {
  name = "${var.project}-pipeline-alerts"

  tags = {
    Project = var.project
    Env     = var.env
  }
}

# 이메일 구독 (terraform apply 후 이메일 확인 필요)
variable "alert_email" {
  description = "파이프라인 알림 수신 이메일"
  type        = string
}

resource "aws_sns_topic_subscription" "pipeline_alerts_email" {
  topic_arn = aws_sns_topic.pipeline_alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# ── Step Functions State Machine ──────────────────────────────────────────────
# TaskDefinition은 패밀리 이름 — 실행 시점의 최신 리비전(CI가 배포마다 커밋 SHA로 등록)을 쓴다
resource "aws_sfn_state_machine" "monthly_pipeline" {
  name     = "${var.project}-monthly-pipeline"
  role_arn = aws_iam_role.sfn_pipeline.arn

  definition = jsonencode({
    Comment = "FSQ 월간 데이터 파이프라인: 추출 → 배치"
    StartAt = "ExtractFSQ"
    States = {
      ExtractFSQ = {
        Type           = "Task"
        Resource       = "arn:aws:states:::ecs:runTask.sync"
        TimeoutSeconds = 3600
        Parameters = {
          LaunchType     = "FARGATE"
          Cluster        = aws_ecs_cluster.main.arn
          TaskDefinition = aws_ecs_task_definition.fsq_extractor.family
          NetworkConfiguration = {
            AwsvpcConfiguration = {
              Subnets        = local.private_subnet_ids
              SecurityGroups = [aws_security_group.app.id]
              AssignPublicIp = "ENABLED"
            }
          }
        }
        Retry = [{
          ErrorEquals     = ["States.TaskFailed"]
          MaxAttempts     = 2
          IntervalSeconds = 300
          BackoffRate     = 2
        }]
        Catch = [{
          ErrorEquals = ["States.ALL"]
          Next        = "NotifyFailure"
          ResultPath  = "$.error"
        }]
        Next = "RunBatch"
      }

      RunBatch = {
        Type     = "Task"
        Resource = "arn:aws:states:::ecs:runTask.sync"
        Parameters = {
          LaunchType     = "FARGATE"
          Cluster        = aws_ecs_cluster.main.arn
          TaskDefinition = aws_ecs_task_definition.batch.family
          NetworkConfiguration = {
            AwsvpcConfiguration = {
              Subnets        = local.private_subnet_ids
              SecurityGroups = [aws_security_group.app.id]
              AssignPublicIp = "ENABLED"
            }
          }
        }
        Retry = [{
          ErrorEquals     = ["States.TaskFailed"]
          MaxAttempts     = 1
          IntervalSeconds = 600
          BackoffRate     = 1
        }]
        Catch = [{
          ErrorEquals = ["States.ALL"]
          Next        = "NotifyFailure"
          ResultPath  = "$.error"
        }]
        Next = "NotifySuccess"
      }

      NotifySuccess = {
        Type     = "Task"
        Resource = "arn:aws:states:::sns:publish"
        Parameters = {
          TopicArn = aws_sns_topic.pipeline_alerts.arn
          Message  = "✅ 월간 FSQ 파이프라인 완료"
          Subject  = "[shgtrip] FSQ 파이프라인 성공"
        }
        End = true
      }

      NotifyFailure = {
        Type     = "Task"
        Resource = "arn:aws:states:::sns:publish"
        Parameters = {
          TopicArn    = aws_sns_topic.pipeline_alerts.arn
          "Message.$" = "States.Format('❌ FSQ 파이프라인 실패: {}', $.error)"
          Subject     = "[shgtrip] FSQ 파이프라인 실패"
        }
        End = true
      }
    }
  })

  tags = {
    Project = var.project
    Env     = var.env
  }
}

# ── EventBridge Scheduler (매월 1일 02:00 KST = 17:00 UTC) ───────────────────
resource "aws_scheduler_schedule" "monthly_pipeline" {
  name       = "${var.project}-monthly-pipeline"
  group_name = "default"

  flexible_time_window {
    mode = "OFF"
  }

  # 매월 1일 17:00 UTC (= 02:00 KST)
  schedule_expression          = "cron(0 17 1 * ? *)"
  schedule_expression_timezone = "UTC"

  target {
    arn      = aws_sfn_state_machine.monthly_pipeline.arn
    role_arn = aws_iam_role.scheduler.arn

    input = jsonencode({
      trigger = "scheduled"
    })
  }
}
