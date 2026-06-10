# ── 데이터 버킷 ───────────────────────────────────────────────────────────────
resource "aws_s3_bucket" "data" {
  bucket = "${var.project}-data"

  tags = {
    Project = var.project
    Env     = var.env
  }
}

# S3 버킷 정책: ECS Task Role에 S3 접근 허용
resource "aws_s3_bucket_policy" "data" {
  bucket = aws_s3_bucket.data.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "AllowECSTaskRoleS3Access"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${aws_iam_role.app_task.name}"
        }
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.data.arn,
          "${aws_s3_bucket.data.arn}/*"
        ]
      }
    ]
  })
}

resource "aws_s3_bucket_versioning" "data" {
  bucket = aws_s3_bucket.data.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "data" {
  bucket = aws_s3_bucket.data.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "data" {
  bucket                  = aws_s3_bucket.data.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# foursquare/ 하위 오래된 파티션 자동 정리 (6개월치만 보관)
resource "aws_s3_bucket_lifecycle_configuration" "data" {
  bucket = aws_s3_bucket.data.id

  rule {
    id     = "foursquare-retention"
    status = "Enabled"

    filter {
      prefix = "foursquare/"
    }

    expiration {
      days = 180
    }
  }
}

# ── Terraform 상태 버킷 (최초 1회 수동 생성 후 import) ────────────────────────
# aws s3 mb s3://shgtrip-terraform-state --region ap-northeast-2
# aws s3api put-bucket-versioning --bucket shgtrip-terraform-state \
#   --versioning-configuration Status=Enabled
