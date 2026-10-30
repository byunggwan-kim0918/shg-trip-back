# ── ECS 서비스 ────────────────────────────────────────────────────────────────
# task_definition은 CI 배포가 바꾸므로 무시한다 — 태스크 정의를 바꾼 apply 뒤에는 배포 워크플로를 수동 실행

resource "aws_ecs_service" "app" {
  name            = "shgtrip-app"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.app.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  health_check_grace_period_seconds = 120
  availability_zone_rebalancing     = "ENABLED"

  load_balancer {
    target_group_arn = aws_lb_target_group.app.arn
    container_name   = "app"
    container_port   = 8080
  }

  network_configuration {
    subnets          = local.private_subnet_ids
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = false
  }

  tags = {
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }

  lifecycle {
    ignore_changes = [task_definition]
  }
}

resource "aws_ecs_service" "web" {
  name            = "shgtrip-web"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.web.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  health_check_grace_period_seconds = 120
  availability_zone_rebalancing     = "ENABLED"

  load_balancer {
    target_group_arn = aws_lb_target_group.web.arn
    container_name   = "web"
    container_port   = 3000
  }

  network_configuration {
    subnets          = local.private_subnet_ids
    security_groups  = [aws_security_group.web.id]
    assign_public_ip = false
  }

  tags = {
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }

  lifecycle {
    ignore_changes = [task_definition]
  }
}
