# ── ECR 공통 보관 정책 ─────────────────────────────────────────────────────────
locals {
  ecr_lifecycle_policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "태그 없는 이미지는 하루 뒤 삭제"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "최근 10개만 보관"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 10
        }
        action = { type = "expire" }
      },
    ]
  })
}

# ── FSQ 추출기 (Python + DuckDB) ─────────────────────────────────────────────
resource "aws_ecr_repository" "fsq_extractor" {
  name                 = "${var.project}-fsq-extractor"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_ecr_lifecycle_policy" "fsq_extractor" {
  repository = aws_ecr_repository.fsq_extractor.name
  policy     = local.ecr_lifecycle_policy
}

# ── Spring Boot 배치 ──────────────────────────────────────────────────────────
resource "aws_ecr_repository" "batch" {
  name                 = "${var.project}-batch"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_ecr_lifecycle_policy" "batch" {
  repository = aws_ecr_repository.batch.name
  policy     = local.ecr_lifecycle_policy
}

# ── 앱·웹 ─────────────────────────────────────────────────────────────────────
resource "aws_ecr_repository" "app" {
  name                 = "shgtrip/app"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy     = local.ecr_lifecycle_policy
}

resource "aws_ecr_repository" "web" {
  name                 = "shgtrip/web"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Project   = var.project
    Env       = var.env
    ManagedBy = "manual"
  }
}

resource "aws_ecr_lifecycle_policy" "web" {
  repository = aws_ecr_repository.web.name
  policy     = local.ecr_lifecycle_policy
}
