# ── 장소 이미지 서빙용 CloudFront (비공개 S3 + OAC) ───────────────────────────
# presigned URL(만료·주기 갱신)을 대체. 만료 없는 정적 URL을 CDN으로 서빙한다.
# 기본 도메인(*.cloudfront.net) 사용 — ACM/Route53 불필요.

# Origin Access Control: CloudFront만 비공개 S3 객체를 SigV4로 서명 접근
resource "aws_cloudfront_origin_access_control" "images" {
  name                              = "${var.project}-images-oac"
  description                       = "OAC for place images bucket"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_distribution" "images" {
  enabled = true
  comment = "${var.project} place images CDN"

  origin {
    domain_name              = aws_s3_bucket.data.bucket_regional_domain_name
    origin_id                = "s3-${aws_s3_bucket.data.id}"
    origin_access_control_id = aws_cloudfront_origin_access_control.images.id
  }

  default_cache_behavior {
    target_origin_id       = "s3-${aws_s3_bucket.data.id}"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    # AWS 관리형 정책: Managed-CachingOptimized
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # 기본 CloudFront 인증서(*.cloudfront.net) — 커스텀 도메인 미사용
  viewer_certificate {
    cloudfront_default_certificate = true
  }

  # PriceClass_200: 북미·유럽 + 아시아(서울 포함) 엣지. 남미·호주·뉴질랜드 제외.
  # 한국 사용자 대상이므로 서울 엣지가 포함된 200이 적합(100은 아시아 제외라 부적합).
  price_class = "PriceClass_200"

  tags = {
    Project = var.project
    Env     = var.env
  }
}
