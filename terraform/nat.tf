# ── NAT 인스턴스 ──────────────────────────────────────────────────────────────
# 프라이빗 서브넷의 유일한 외부 출구 — 멈추면 ECR pull·Secrets Manager·외부 API 호출이 전부 끊긴다

resource "aws_instance" "nat" {
  ami                         = "ami-0ee840e948c0ac14b"
  instance_type               = "t3.micro"
  subnet_id                   = aws_subnet.public_2a.id
  vpc_security_group_ids      = [aws_security_group.nat.id]
  associate_public_ip_address = true
  # 자기 주소가 아닌 패킷을 중계하려면 꺼야 한다.
  source_dest_check    = false
  iam_instance_profile = aws_iam_instance_profile.ec2_ssm.name
  user_data            = file("${path.module}/nat-userdata.sh")

  credit_specification {
    cpu_credits = "unlimited"
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
  }

  tags = {
    Name      = "shgTrip-dev-nat"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }

  # user_data는 첫 부팅 때만 쓰이는데 바뀌면(끝 줄바꿈 하나로도) provider가 인스턴스를 재시작한다.
  # 스크립트를 바꾸려면 -replace=aws_instance.nat로 교체한다
  lifecycle {
    ignore_changes = [user_data]
  }
}

resource "aws_security_group" "nat" {
  name        = "shgTrip-nat-sg"
  description = "NAT instance"
  vpc_id      = aws_vpc.main.id

  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [aws_vpc.main.cidr_block]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name      = "shgTrip-nat-sg"
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

# SSM Session Manager 접속용
resource "aws_iam_role" "ec2_ssm" {
  name        = "shgTrip-ec2-ssm-role"
  description = "Allows EC2 instances to call AWS services on your behalf."
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ec2_ssm" {
  role       = aws_iam_role.ec2_ssm.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "ec2_ssm" {
  name = "shgTrip-ec2-ssm-role"
  role = aws_iam_role.ec2_ssm.name
}
