# ── VPC / 서브넷 / 라우팅 ──────────────────────────────────────────────────────

resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = false

  tags = {
    Name      = "shgTrip-dev-vpc"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_subnet" "public_2a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.1.0/24"
  availability_zone = "ap-northeast-2a"

  tags = {
    Name      = "shgTrip-public-subnet-2a"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_subnet" "public_2c" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.2.0/24"
  availability_zone = "ap-northeast-2c"

  tags = {
    Name      = "shgTrip-public-subnet-2c"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_subnet" "private_2a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.10.0/24"
  availability_zone = "ap-northeast-2a"

  tags = {
    Name      = "shgTrip-private-subnet-2a"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_subnet" "private_2c" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.20.0/24"
  availability_zone = "ap-northeast-2c"

  tags = {
    Name      = "shgTrip-private-subnet-2c"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

locals {
  public_subnet_ids  = [aws_subnet.public_2a.id, aws_subnet.public_2c.id]
  private_subnet_ids = [aws_subnet.private_2a.id, aws_subnet.private_2c.id]
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name      = "shgTrip-igw"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

# VPC의 main 라우트 테이블 — 연결을 안 한 새 서브넷은 퍼블릭이 된다
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name      = "shgTrip-public-rt"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

# NAT 게이트웨이 대신 NAT 인스턴스 — 월 $40대 고정비 회피
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block           = "0.0.0.0/0"
    network_interface_id = aws_instance.nat.primary_network_interface_id
  }

  tags = {
    Name      = "shgTrip-private-rt"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_route_table_association" "public_2a" {
  subnet_id      = aws_subnet.public_2a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_2c" {
  subnet_id      = aws_subnet.public_2c.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private_2a" {
  subnet_id      = aws_subnet.private_2a.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "private_2c" {
  subnet_id      = aws_subnet.private_2c.id
  route_table_id = aws_route_table.private.id
}
