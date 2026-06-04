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
  default     = "kbg0918/shg-trip-back"
}
