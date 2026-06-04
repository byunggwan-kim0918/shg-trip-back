output "data_bucket_name" {
  description = "S3 데이터 버킷명"
  value       = aws_s3_bucket.data.bucket
}

output "ecr_extractor_url" {
  description = "FSQ 추출기 ECR 이미지 URL"
  value       = aws_ecr_repository.fsq_extractor.repository_url
}

output "ecr_batch_url" {
  description = "Spring Boot 배치 ECR 이미지 URL"
  value       = aws_ecr_repository.batch.repository_url
}

output "github_terraform_role_arn" {
  description = "GitHub Actions Terraform OIDC Role ARN"
  value       = aws_iam_role.github_terraform.arn
}

output "github_deploy_role_arn" {
  description = "GitHub Actions Deploy OIDC Role ARN"
  value       = aws_iam_role.github_deploy.arn
}
