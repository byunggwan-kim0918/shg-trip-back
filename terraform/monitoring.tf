# ── 장애 알림 ─────────────────────────────────────────────────────────────────
# CloudWatch 알람은 10개까지 무료

locals {
  alert_topic_arn = aws_sns_topic.pipeline_alerts.arn
}

# 토픽의 기본 정책을 통째로 대체하므로 첫 문장(같은 계정 발행)을 빼면 CloudWatch 알람이 발행하지 못한다
data "aws_iam_policy_document" "alerts_topic" {
  statement {
    sid = "AccountOwner"
    principals {
      type        = "AWS"
      identifiers = ["*"]
    }
    actions = [
      "SNS:Publish", "SNS:Subscribe", "SNS:Receive", "SNS:GetTopicAttributes", "SNS:SetTopicAttributes",
      "SNS:AddPermission", "SNS:RemovePermission", "SNS:DeleteTopic", "SNS:ListSubscriptionsByTopic",
    ]
    resources = [local.alert_topic_arn]
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceOwner"
      values   = [local.account_id]
    }
  }

  statement {
    sid = "EventBridgePublish"
    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }
    actions   = ["sns:Publish"]
    resources = [local.alert_topic_arn]
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_cloudwatch_event_rule.ecs_deploy_failed.arn]
    }
  }
}

resource "aws_sns_topic_policy" "alerts" {
  arn    = local.alert_topic_arn
  policy = data.aws_iam_policy_document.alerts_topic.json
}

# ── 배포 실패 (circuit breaker 롤백) ──────────────────────────────────────────
resource "aws_cloudwatch_event_rule" "ecs_deploy_failed" {
  name = "${var.project}-ecs-deploy-failed"
  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Deployment State Change", "ECS Service Action"]
    detail = {
      eventName = ["SERVICE_DEPLOYMENT_FAILED", "SERVICE_TASK_START_IMPAIRED"]
    }
  })
}

resource "aws_cloudwatch_event_target" "ecs_deploy_failed" {
  rule = aws_cloudwatch_event_rule.ecs_deploy_failed.name
  arn  = local.alert_topic_arn

  input_transformer {
    input_paths = {
      event   = "$.detail.eventName"
      reason  = "$.detail.reason"
      service = "$.resources[0]"
      time    = "$.time"
    }
    input_template = "\"[shgtrip] <event> <service> (<time>) <reason>\""
  }
}

# ── 서비스 다운: 정상 타깃 0개 ────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "app_no_healthy_target" {
  alarm_name          = "${var.project}-app-no-healthy-target"
  alarm_description   = "백엔드 정상 타깃 0개 (3분)"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"
  dimensions = {
    LoadBalancer = aws_lb.private.arn_suffix
    TargetGroup  = aws_lb_target_group.app.arn_suffix
  }
  alarm_actions = [local.alert_topic_arn]
  ok_actions    = [local.alert_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "web_no_healthy_target" {
  alarm_name          = "${var.project}-web-no-healthy-target"
  alarm_description   = "웹 정상 타깃 0개 (3분)"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  statistic           = "Minimum"
  period              = 60
  evaluation_periods  = 3
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  treat_missing_data  = "breaching"
  dimensions = {
    LoadBalancer = aws_lb.public.arn_suffix
    TargetGroup  = aws_lb_target_group.web.arn_suffix
  }
  alarm_actions = [local.alert_topic_arn]
  ok_actions    = [local.alert_topic_arn]
}

# ── 5xx ───────────────────────────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "public_elb_5xx" {
  alarm_name          = "${var.project}-public-elb-5xx"
  alarm_description   = "public ALB 자체 5xx 5분간 10건 이상"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_ELB_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 10
  treat_missing_data  = "notBreaching"
  dimensions = {
    LoadBalancer = aws_lb.public.arn_suffix
  }
  alarm_actions = [local.alert_topic_arn]
  ok_actions    = [local.alert_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "app_5xx" {
  alarm_name          = "${var.project}-app-5xx"
  alarm_description   = "백엔드 5xx 5분간 10건 이상"
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 10
  treat_missing_data  = "notBreaching"
  dimensions = {
    LoadBalancer = aws_lb.private.arn_suffix
  }
  alarm_actions = [local.alert_topic_arn]
  ok_actions    = [local.alert_topic_arn]
}

# ── RDS ───────────────────────────────────────────────────────────────────────
resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${var.project}-rds-free-storage"
  alarm_description   = "RDS 남은 저장공간 2GiB 미만 (할당 20GiB)"
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  comparison_operator = "LessThanThreshold"
  threshold           = 2 * 1024 * 1024 * 1024
  treat_missing_data  = "notBreaching"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
  alarm_actions = [local.alert_topic_arn]
  ok_actions    = [local.alert_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.project}-rds-cpu"
  alarm_description   = "RDS CPU 80% 이상 15분"
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 80
  treat_missing_data  = "notBreaching"
  dimensions = {
    DBInstanceIdentifier = aws_db_instance.main.identifier
  }
  alarm_actions = [local.alert_topic_arn]
  ok_actions    = [local.alert_topic_arn]
}

# ── NAT 인스턴스 ──────────────────────────────────────────────────────────────
# 단일 장애점이라 자동 복구·재부팅까지 건다. 일부러 멈춰 둔 동안은 동작하지 않게 데이터 없음은 판정하지 않는다
resource "aws_cloudwatch_metric_alarm" "nat_system_check" {
  alarm_name          = "${var.project}-nat-system-check"
  alarm_description   = "NAT 인스턴스 시스템 상태 검사 실패 — 다른 호스트로 자동 복구"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_System"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "missing"
  dimensions = {
    InstanceId = aws_instance.nat.id
  }
  alarm_actions = ["arn:aws:automate:${var.aws_region}:ec2:recover", local.alert_topic_arn]
  ok_actions    = [local.alert_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "nat_instance_check" {
  alarm_name          = "${var.project}-nat-instance-check"
  alarm_description   = "NAT 인스턴스 상태 검사 실패 — 재부팅"
  namespace           = "AWS/EC2"
  metric_name         = "StatusCheckFailed_Instance"
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 3
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "missing"
  dimensions = {
    InstanceId = aws_instance.nat.id
  }
  alarm_actions = ["arn:aws:automate:${var.aws_region}:ec2:reboot", local.alert_topic_arn]
  ok_actions    = [local.alert_topic_arn]
}
