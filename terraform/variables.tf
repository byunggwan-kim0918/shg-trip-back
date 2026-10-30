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

variable "github_repo" {
  description = "GitHub 저장소 (owner/repo 형식)"
  type        = string
  default     = "byunggwan-kim0918/shg-trip-back"
}

variable "github_repo_front" {
  description = "프론트엔드 GitHub 저장소 (owner/repo 형식)"
  type        = string
  default     = "byunggwan-kim0918/shg-trip-front"
}
