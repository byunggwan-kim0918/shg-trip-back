variable "aws_region" {
  description = "AWS 리전"
  type        = string
  default     = "ap-northeast-2"
}

variable "project" {
  description = "프로젝트 이름 (리소스 네이밍에 사용)"
  type        = string
  default     = "shgtrip"
}

variable "env" {
  description = "환경 (prod / staging)"
  type        = string
  default     = "prod"
}

variable "fsq_access_token" {
  description = "Foursquare Places Portal 액세스 토큰"
  type        = string
  sensitive   = true
}

variable "github_repo" {
  description = "GitHub 저장소 (owner/repo 형식)"
  type        = string
  default     = "byunggwan-kim0918/shg-trip-back"
}

variable "app_security_group_id" {
  description = "백엔드 앱 보안그룹 ID (shgTrip-app-sg)"
  type        = string
}

variable "web_security_group_id" {
  description = "프론트엔드 웹 보안그룹 ID (shgTrip-web-sg)"
  type        = string
}

variable "github_repo_front" {
  description = "프론트엔드 GitHub 저장소 (owner/repo 형식)"
  type        = string
  default     = "byunggwan-kim0918/shg-trip-front"
}

variable "vpc_id" {
  description = "VPC ID (Target Group 생성에 필요)"
  type        = string
}
