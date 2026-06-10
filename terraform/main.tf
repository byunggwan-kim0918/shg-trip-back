terraform {
  required_version = ">= 1.6"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # tfstate를 S3에 저장 (팀 협업 및 상태 보존)
  backend "s3" {
    bucket         = "shgtrip-terraform-state"
    key            = "shgtrip/terraform.tfstate"
    region         = "ap-northeast-2"
    encrypt        = true
    dynamodb_table = "shgtrip-terraform-lock"
  }
}

provider "aws" {
  region = var.aws_region
}

# 현재 AWS 계정 ID 조회 (S3 버킷 정책에서 사용)
data "aws_caller_identity" "current" {}
