# ── GitHub Actions OIDC Provider ──────────────────────────────────────────────
# GitHub → AWS 인증을 위한 OIDC IdP (계정당 1개만 존재 가능)

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = {
    Project = var.project
    Env     = var.env
  }
}

# ── Terraform CI/CD Role ─────────────────────────────────────────────────────
data "aws_iam_policy_document" "github_terraform_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:*"]
    }
  }
}

resource "aws_iam_role" "github_terraform" {
  name               = "${var.project}-github-terraform"
  assume_role_policy = data.aws_iam_policy_document.github_terraform_assume.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

# Terraform은 거의 모든 리소스를 관리하므로 AdministratorAccess 부여
# 프로덕션에서는 권한을 줄일 수 있음
resource "aws_iam_role_policy_attachment" "github_terraform" {
  role       = aws_iam_role.github_terraform.name
  policy_arn = "arn:aws:iam::aws:policy/AdministratorAccess"
}

# ── 앱 배포 Role (ECR push + ECS deploy) ─────────────────────────────────────
data "aws_iam_policy_document" "github_deploy_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    # push: main 트리거 + environment: production 사용 시
    # sub가 "repo:...:environment:production" 형태로 옴
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repo}:ref:refs/heads/main",
        "repo:${var.github_repo}:environment:production",
      ]
    }
  }
}

resource "aws_iam_role" "github_deploy" {
  name               = "${var.project}-github-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_deploy_assume.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name = "${var.project}-github-deploy-policy"
  role = aws_iam_role.github_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "ECRAuth"
        Effect = "Allow"
        Action = [
          "ecr:GetAuthorizationToken"
        ]
        Resource = "*"
      },
      {
        Sid    = "ECRPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:PutImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload"
        ]
        Resource = [
          aws_ecr_repository.batch.arn,
          "arn:aws:ecr:${var.aws_region}:${local.account_id}:repository/shgtrip/app"
        ]
      },
      {
        Sid    = "ECSDeployApp"
        Effect = "Allow"
        Action = [
          "ecs:UpdateService",
          "ecs:DescribeServices",
          "ecs:DescribeTaskDefinition",
          "ecs:RegisterTaskDefinition",
          "ecs:DeregisterTaskDefinition"
        ]
        Resource = "*"
      },
      {
        Sid    = "PassRole"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          aws_iam_role.ecs_execution.arn,
          aws_iam_role.batch_task.arn,
          aws_iam_role.app_task.arn
        ]
      }
    ]
  })
}

# ── 프론트엔드 배포 Role (shg-trip-front 리포 전용) ──────────────────────────
data "aws_iam_policy_document" "github_deploy_front_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repo_front}:ref:refs/heads/main",
        "repo:${var.github_repo_front}:environment:production",
      ]
    }
  }
}

resource "aws_iam_role" "github_deploy_front" {
  name               = "${var.project}-github-deploy-front"
  assume_role_policy = data.aws_iam_policy_document.github_deploy_front_assume.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_iam_role_policy" "github_deploy_front" {
  name = "${var.project}-github-deploy-front-policy"
  role = aws_iam_role.github_deploy_front.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ECRAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "ECRPushWeb"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
          "ecr:PutImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload"
        ]
        # shgtrip/web 리포 (콘솔 생성 → ARN 직접 명시)
        Resource = ["arn:aws:ecr:${var.aws_region}:${local.account_id}:repository/shgtrip/web"]
      },
      {
        Sid    = "ECSDeployWeb"
        Effect = "Allow"
        Action = [
          "ecs:UpdateService",
          "ecs:DescribeServices",
          "ecs:DescribeTaskDefinition",
          "ecs:RegisterTaskDefinition"
        ]
        Resource = "*"
      },
      {
        Sid    = "PassRole"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [
          aws_iam_role.ecs_execution.arn,
          aws_iam_role.web_task.arn
        ]
      }
    ]
  })
}
