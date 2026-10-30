# ── 로드밸런서 ────────────────────────────────────────────────────────────────

# DNS 검증 레코드가 Cloudflare에 있어서 aws_acm_certificate_validation은 두지 않는다
resource "aws_acm_certificate" "main" {
  domain_name               = "shg-trip.cloud"
  subject_alternative_names = ["*.shg-trip.cloud", "shg-trip.cloud"]
  validation_method         = "DNS"
  key_algorithm             = "RSA_2048"

  tags = {
    Name      = "shg-trip.cloud"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb" "public" {
  name               = "shgTrip-public-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [aws_security_group.public_alb.id]
  subnets            = local.public_subnet_ids
  client_keep_alive  = 3600

  tags = {
    Name      = "shgTrip-public-alb"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_lb" "private" {
  name               = "shgTrip-private-alb"
  internal           = true
  load_balancer_type = "application"
  security_groups    = [aws_security_group.private_alb.id]
  subnets            = local.private_subnet_ids
  client_keep_alive  = 3600

  tags = {
    Name      = "shgTrip-private-alb"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_lb_target_group" "web" {
  name        = "shgtrip-web-tg"
  port        = 3000
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  health_check {
    path                = "/health"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name      = "shgtrip-web-tg"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_lb_target_group" "app" {
  name        = "shgtrip-app-tg"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = {
    Name      = "shgtrip-app-tg"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_lb_listener" "public_https" {
  load_balancer_arn = aws_lb.public.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-Res-PQ-2025-09"
  certificate_arn   = aws_acm_certificate.main.arn

  # AWS가 단축형과 forward 블록을 둘 다 돌려줘서 둘 다 써야 plan에 diff가 안 생긴다
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.web.arn

    forward {
      target_group {
        arn    = aws_lb_target_group.web.arn
        weight = 1
      }

      stickiness {
        enabled  = false
        duration = 3600
      }
    }
  }
}

resource "aws_lb_listener" "public_http" {
  load_balancer_arn = aws_lb.public.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "private_http" {
  load_balancer_arn = aws_lb.private.arn
  port              = 8080
  protocol          = "HTTP"

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn

    forward {
      target_group {
        arn    = aws_lb_target_group.app.arn
        weight = 1
      }

      stickiness {
        enabled  = false
        duration = 3600
      }
    }
  }
}
