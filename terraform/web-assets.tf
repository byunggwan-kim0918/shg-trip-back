# ── 프론트 정적 파일 CDN (Next.js assetPrefix) ───────────────────────────────
# 이전 빌드 파일이 남아 있어야 롤링 배포 중이나 배포 전에 열린 탭의 청크 요청이 404가 나지 않는다.
# 그래서 배포 Role에는 삭제 권한을 주지 않는다.

resource "aws_s3_bucket" "web_assets" {
  bucket = "${var.project}-web-assets-${data.aws_caller_identity.current.account_id}"

  tags = {
    Project = var.project
    Env     = var.env
  }
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "web_assets" {
  bucket = aws_s3_bucket.web_assets.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "web_assets" {
  bucket                  = aws_s3_bucket.web_assets.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "web_assets" {
  bucket = aws_s3_bucket.web_assets.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowCloudFrontOACRead"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.web_assets.arn}/_next/static/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.web_assets.arn
          }
        }
      }
    ]
  })
}

resource "aws_cloudfront_origin_access_control" "web_assets" {
  name                              = "${var.project}-web-assets-oac"
  description                       = "OAC for web static assets bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

# 폰트는 페이지와 출처가 달라 CORS 헤더가 없으면 브라우저가 막는다.
data "aws_cloudfront_response_headers_policy" "simple_cors" {
  name = "Managed-SimpleCORS"
}

resource "aws_cloudfront_distribution" "web_assets" {
  enabled = true
  comment = "${var.project} web static assets CDN"

  origin {
    domain_name              = aws_s3_bucket.web_assets.bucket_regional_domain_name
    origin_id                = "s3-${aws_s3_bucket.web_assets.id}"
    origin_access_control_id = aws_cloudfront_origin_access_control.web_assets.id
  }

  default_cache_behavior {
    target_origin_id           = "s3-${aws_s3_bucket.web_assets.id}"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.simple_cors.id
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }

  price_class = "PriceClass_200"

  tags = {
    Project = var.project
    Env     = var.env
  }
}
