# ── 보안그룹 ──────────────────────────────────────────────────────────────────
# 흐름: 인터넷 → public ALB → web(3000) → private ALB → app(8080) → db(5432) / valkey(6379)

resource "aws_security_group" "public_alb" {
  name        = "shgTrip-public-alb-sg"
  description = "Public ALB - internet facing"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "shgTrip-public-alb-sg"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_security_group" "web" {
  name        = "shgTrip-web-sg"
  description = "Next.js web containers"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 3000
    to_port         = 3000
    protocol        = "tcp"
    security_groups = [aws_security_group.public_alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "shgTrip-web-sg"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_security_group" "private_alb" {
  name        = "shgTrip-private-alb-sg"
  description = "Private ALB - internal"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.web.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "shgTrip-private-alb-sg"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

# 앱 서비스와 월간 배치 태스크가 같이 쓴다
resource "aws_security_group" "app" {
  name        = "shgTrip-app-sg"
  description = "Spring Boot app containers"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.private_alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "shgTrip-app-sg"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_security_group" "db" {
  name        = "shgTrip-db-sg"
  description = "RDS PostgreSQL"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "shgTrip-db-sg"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_security_group" "valkey" {
  name        = "shgTrip-valkey-sg"
  description = "ElastiCache Valkey"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "shgTrip-valkey-sg"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}
