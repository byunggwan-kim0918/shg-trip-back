# ── 콘솔 리소스 편입 ──────────────────────────────────────────────────────────
# 편입 apply가 끝나면 지운다 — 새 계정에서는 대상이 없어 plan이 실패한다

# 네트워크
import {
  to = aws_vpc.main
  id = "vpc-0b7b0bb5a1faef765"
}
import {
  to = aws_subnet.public_2a
  id = "subnet-0fcc61667e84e8f8f"
}
import {
  to = aws_subnet.public_2c
  id = "subnet-0978b0d4e2484ae6c"
}
import {
  to = aws_subnet.private_2a
  id = "subnet-06ca4af51f0e89449"
}
import {
  to = aws_subnet.private_2c
  id = "subnet-05dd78684a96f6432"
}
import {
  to = aws_internet_gateway.main
  id = "igw-03ffbbf5ed8af0157"
}
import {
  to = aws_route_table.public
  id = "rtb-0a1ab703756c22652"
}
import {
  to = aws_route_table.private
  id = "rtb-086c5acb5ea79402e"
}
import {
  to = aws_route_table_association.public_2a
  id = "subnet-0fcc61667e84e8f8f/rtb-0a1ab703756c22652"
}
import {
  to = aws_route_table_association.public_2c
  id = "subnet-0978b0d4e2484ae6c/rtb-0a1ab703756c22652"
}
import {
  to = aws_route_table_association.private_2a
  id = "subnet-06ca4af51f0e89449/rtb-086c5acb5ea79402e"
}
import {
  to = aws_route_table_association.private_2c
  id = "subnet-05dd78684a96f6432/rtb-086c5acb5ea79402e"
}

# NAT 인스턴스
import {
  to = aws_instance.nat
  id = "i-0f071b8ea015874c5"
}
import {
  to = aws_iam_role.ec2_ssm
  id = "shgTrip-ec2-ssm-role"
}
import {
  to = aws_iam_instance_profile.ec2_ssm
  id = "shgTrip-ec2-ssm-role"
}
import {
  to = aws_iam_role_policy_attachment.ec2_ssm
  id = "shgTrip-ec2-ssm-role/arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# 보안그룹
import {
  to = aws_security_group.nat
  id = "sg-0a32c9b0a4547408e"
}
import {
  to = aws_security_group.public_alb
  id = "sg-0ea1b7c2d403f0e5a"
}
import {
  to = aws_security_group.private_alb
  id = "sg-0e221ccec959485db"
}
import {
  to = aws_security_group.web
  id = "sg-08ccab7f0ff237d98"
}
import {
  to = aws_security_group.app
  id = "sg-0cdbc22c6923199c1"
}
import {
  to = aws_security_group.db
  id = "sg-0caf7fe22b4f695f7"
}
import {
  to = aws_security_group.valkey
  id = "sg-0f91ab8f3335b8a86"
}

# 로드밸런서
import {
  to = aws_acm_certificate.main
  id = "arn:aws:acm:ap-northeast-2:815734209917:certificate/9499807f-c856-4d82-9aa9-dff350340e69"
}
import {
  to = aws_lb.public
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:815734209917:loadbalancer/app/shgTrip-public-alb/5c467c574082cfee"
}
import {
  to = aws_lb.private
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:815734209917:loadbalancer/app/shgTrip-private-alb/83dbb7491594cc4e"
}
import {
  to = aws_lb_target_group.app
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:815734209917:targetgroup/shgtrip-app-tg/59933707ce11cac2"
}
import {
  to = aws_lb_target_group.web
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:815734209917:targetgroup/shgtrip-web-tg/988da3e4faefc737"
}
import {
  to = aws_lb_listener.public_https
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:815734209917:listener/app/shgTrip-public-alb/5c467c574082cfee/61dc2024c78892d6"
}
import {
  to = aws_lb_listener.public_http
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:815734209917:listener/app/shgTrip-public-alb/5c467c574082cfee/7c8b05245bc96644"
}
import {
  to = aws_lb_listener.private_http
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:815734209917:listener/app/shgTrip-private-alb/83dbb7491594cc4e/1b7a1c26df41fd56"
}

# ECS 서비스
import {
  to = aws_ecs_service.app
  id = "shgtrip-cluster/shgtrip-app"
}
import {
  to = aws_ecs_service.web
  id = "shgtrip-cluster/shgtrip-web"
}

# ECR
import {
  to = aws_ecr_repository.app
  id = "shgtrip/app"
}
import {
  to = aws_ecr_repository.web
  id = "shgtrip/web"
}

# 비용 예산
import {
  to = aws_budgets_budget.monthly
  id = "815734209917:shgtrip-prod-monthly-budget"
}

# Valkey 슬로우 로그
import {
  to = aws_cloudwatch_log_group.valkey_slow_log
  id = "/elasticache/shgtrip-valkey/slow-logs"
}
