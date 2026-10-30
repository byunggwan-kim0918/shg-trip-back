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

# ── Terraform CI/CD Role (plan / apply 분리) ──────────────────────────────────
# 예전엔 역할 하나(AdministratorAccess)를 저장소의 모든 브랜치·워크플로가 쓸 수 있었다
# (sub = "repo:...:*"). 워크플로의 action 하나만 털려도 계정 전체가 넘어가는 구조라 둘로 나눈다.
#   plan  — PR에서만. 읽기 전용이라 리소스를 바꿀 수 없다.
#   apply — GitHub 환경 "infrastructure"에서만. 그 환경에 필수 승인자·main 브랜치 제한을
#           걸어 두므로(저장소 설정, 수동) 사람이 승인해야만 이 역할이 발급된다.
# sub는 이벤트에 따라 정해진다: pull_request 잡은 "repo:<repo>:pull_request",
# environment가 지정된 잡은 "repo:<repo>:environment:<이름>".

data "aws_iam_policy_document" "github_terraform_plan_assume" {
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
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:pull_request"]
    }
  }
}

resource "aws_iam_role" "github_terraform_plan" {
  name               = "${var.project}-github-terraform-plan"
  assume_role_policy = data.aws_iam_policy_document.github_terraform_plan_assume.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

resource "aws_iam_role_policy_attachment" "github_terraform_plan_readonly" {
  role       = aws_iam_role.github_terraform_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

# ReadOnlyAccess에 없는데 plan에 필요한 것: 상태 잠금(쓰기), 시크릿 값 읽기(시크릿 버전 리소스를
# refresh할 때 GetSecretValue를 부른다). 리소스를 바꾸는 권한은 넣지 않는다.
data "aws_iam_policy_document" "github_terraform_plan_extra" {
  statement {
    sid       = "StateLock"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem"]
    resources = ["arn:aws:dynamodb:${var.aws_region}:${local.account_id}:table/shgtrip-terraform-lock"]
  }
  statement {
    sid     = "ReadManagedSecrets"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_secretsmanager_secret.app_prod.arn,
      aws_secretsmanager_secret.fsq_token.arn,
    ]
  }
}

resource "aws_iam_role_policy" "github_terraform_plan_extra" {
  name   = "${var.project}-github-terraform-plan-extra"
  role   = aws_iam_role.github_terraform_plan.id
  policy = data.aws_iam_policy_document.github_terraform_plan_extra.json
}

data "aws_iam_policy_document" "github_terraform_apply_assume" {
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
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:environment:infrastructure"]
    }
  }
}

resource "aws_iam_role" "github_terraform_apply" {
  name               = "${var.project}-github-terraform-apply"
  assume_role_policy = data.aws_iam_policy_document.github_terraform_apply_assume.json

  tags = {
    Project = var.project
    Env     = var.env
  }
}

# apply는 IAM 역할까지 만들기 때문에 권한을 좁혀도 스스로 권한을 올릴 수 있다(권한 경계 없이는
# 의미가 없다). 그래서 권한은 유지하고, 누가·언제 이 역할을 받을 수 있는지(위 trust + 환경 승인)를 잠근다.
resource "aws_iam_role_policy_attachment" "github_terraform_apply" {
  role       = aws_iam_role.github_terraform_apply.name
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
    # 배포 잡은 environment: production을 쓰므로 sub가 environment 형식으로만 온다.
    # ref:main을 같이 허용하면 environment 없는 워크플로도 이 역할을 받을 수 있다.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo}:environment:production"]
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
          aws_ecr_repository.fsq_extractor.arn,
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
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repo_front}:environment:production"]
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
