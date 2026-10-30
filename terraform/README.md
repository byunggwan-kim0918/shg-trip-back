# Terraform 인프라

## 최초 세팅 순서

### 1. tfstate 백엔드 버킷 수동 생성 (1회)

> ⚠️ S3 버킷명은 전 세계에서 유일해야 한다. `shgtrip-terraform-state` / `shgtrip-data`는
> 이미 타인이 점유 중이라 사용 불가. 계정 ID를 접미사로 붙여 충돌을 피한다.
> (`main.tf`의 backend `bucket`, `s3.tf`의 데이터 버킷명이 이 규칙을 따름)

```bash
aws s3 mb s3://shgtrip-terraform-state-<ACCOUNT_ID> --region ap-northeast-2
aws s3api put-bucket-versioning \
  --bucket shgtrip-terraform-state-<ACCOUNT_ID> \
  --versioning-configuration Status=Enabled
aws s3api put-public-access-block \
  --bucket shgtrip-terraform-state-<ACCOUNT_ID> \
  --public-access-block-configuration \
      BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws dynamodb create-table \
  --table-name shgtrip-terraform-lock \
  --attribute-definitions AttributeName=LockID,AttributeType=S \
  --key-schema AttributeName=LockID,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST \
  --region ap-northeast-2
```

### 2. tfvars 파일 생성
```bash
cp terraform.tfvars.example terraform.tfvars
# terraform.tfvars 편집 (실제 값 입력)

# CI(.github/workflows/terraform.yml)가 여기서 받아 쓴다. 로컬에서 고칠 때마다 다시 올릴 것
aws s3 cp terraform.tfvars s3://shgtrip-terraform-state-<ACCOUNT_ID>/config/terraform.tfvars --sse AES256
```

### 3. Terraform 실행
```bash
terraform init
terraform plan
terraform apply
```

CI 역할(`shgtrip-github-terraform-plan`/`-apply`)은 이 apply가 만든다. 그래서 첫 apply는 반드시 로컬에서 하고,
그다음에 워크플로 변경을 main에 반영한다(역할이 없으면 CI의 plan/apply가 assume 단계에서 실패한다).

새 계정에서 처음 만들 때는 DB 마스터 비밀번호를 그때만 넘긴다: `terraform apply -var db_password=...`
그다음 시크릿 값을 넣는다(Terraform은 그릇만 만든다):
```bash
terraform/scripts/set-secret.sh fsq                  # FSQ 토큰
terraform/scripts/set-secret.sh app DB_PASSWORD      # shg-trip/prod 키마다 (목록: terraform.tfvars.example)
```

### 설정을 바꾼 뒤 반영하기
- **태스크 정의(환경변수·시크릿 참조)를 바꾼 apply**: 실행 중인 서비스는 CI가 등록한 리비전을 계속 쓴다(Terraform은
  서비스의 `task_definition`을 무시한다 — `ecs-service.tf`). GitHub Actions에서 **Deploy Backend / Deploy Frontend를
  수동 실행(Run workflow)** 한다 — 방금 apply한 태스크 정의에 현재 커밋 SHA 이미지를 얹어 다시 배포한다.
  `--force-new-deployment`는 쓰지 말 것: 서비스가 쓰던 옛 리비전 그대로 재시작해서 바뀐 설정이 안 들어간다.
- **시크릿 값만 바꾼 경우**(`set-secret.sh`): 태스크 정의는 그대로이고 컨테이너가 시작할 때 값을 읽으므로 재시작만 하면 된다.
  ```bash
  aws ecs update-service --cluster shgtrip-cluster --service shgtrip-app --force-new-deployment
  aws ecs update-service --cluster shgtrip-cluster --service shgtrip-web --force-new-deployment
  ```
  배치·추출기는 다음 월간 실행부터 새 값을 읽는다.

### 4. ECR에 이미지 푸시

새 계정에서 처음 한 번만 손으로 올린다. Terraform 태스크 정의는 `:latest`를 가리키는 틀이고, 이후로는 CI가
배포마다 커밋 SHA 태그로 push하고 그 SHA를 고정한 리비전을 등록한다(앱·웹 서비스, 배치·추출기 모두).

FSQ 추출기:
```bash
aws ecr get-login-password --region ap-northeast-2 \
  | docker login --username AWS \
    --password-stdin <account_id>.dkr.ecr.ap-northeast-2.amazonaws.com

docker build -t shgtrip-fsq-extractor ./fsq-extractor
docker tag shgtrip-fsq-extractor:latest \
  <account_id>.dkr.ecr.ap-northeast-2.amazonaws.com/shgtrip-fsq-extractor:latest
docker push \
  <account_id>.dkr.ecr.ap-northeast-2.amazonaws.com/shgtrip-fsq-extractor:latest
```

Spring Boot 배치:
```bash
docker build -t shgtrip-batch .
docker tag shgtrip-batch:latest \
  <account_id>.dkr.ecr.ap-northeast-2.amazonaws.com/shgtrip-batch:latest
docker push \
  <account_id>.dkr.ecr.ap-northeast-2.amazonaws.com/shgtrip-batch:latest
```

### 5. 수동 테스트 실행
```bash
# Step Functions 콘솔에서 직접 실행하거나 CLI로:
aws stepfunctions start-execution \
  --state-machine-arn arn:aws:states:ap-northeast-2:<account_id>:stateMachine:shgtrip-monthly-pipeline \
  --input '{"trigger":"manual"}'
```

## 파이프라인 구조

```
EventBridge Scheduler (매월 1일 02:00 KST)
    ↓
Step Functions
    ├── ExtractFSQ  (ECS Fargate - Python/DuckDB)
    │     FSQ Iceberg → KR/JP 필터링 → S3 CSV 저장
    │     실패 시 2회 재시도, 그래도 실패면 SNS 알림
    ↓ 완료 후
    ├── RunBatch    (ECS Fargate - Spring Boot)
    │     S3 CSV → RDS upsert → pgvector 임베딩
    │     실패 시 1회 재시도, 그래도 실패면 SNS 알림
    ↓
    └── SNS 알림 (성공/실패)
```

## 주의사항

- `terraform.tfvars`는 절대 git에 커밋하지 말 것 (.gitignore 확인). 시크릿은 tfvars에 넣지 않는다 — 값은
  `terraform/scripts/set-secret.sh`로 Secrets Manager에 직접 넣는다. FSQ 토큰 월간 갱신도 `set-secret.sh fsq` 한 줄이다.
- 2026-10-30 이전 state·tfvars의 S3 이전 버전(버킷 버저닝)에는 시크릿이 평문으로 남아 있다. 버킷은 비공개·암호화지만,
  유출이 의심되면 S3 이전 버전 삭제가 아니라 해당 키 교체로 대응한다.
- 장애 알림(`monitoring.tf`)과 파이프라인 알림은 `alert_email`로 간다. 주소를 바꾸면 새 주소로 온 SNS 구독 확인 메일을
  눌러야 한다 — 누르기 전엔 알림이 가지 않는다(스팸함에 잘 들어간다). 예산 알림은 `budget_alert_email`.
- 월간 파이프라인(Step Functions)은 태스크 정의 **패밀리 이름**으로 실행한다 → 최신 ACTIVE 리비전 = CI가 마지막으로
  등록한 SHA 리비전. 특정 커밋으로 되돌리려면 그 SHA 이미지로 리비전을 등록하거나 이후 리비전을 해제(deregister)한다.
- VPC·서브넷·보안그룹·ALB·NAT·ECS 서비스도 Terraform이 관리한다(2026-10-30 콘솔 리소스 편입). 콘솔에서 고치면
  다음 apply가 되돌린다 — 코드로 고칠 것. 예외: tfstate 버킷·잠금 테이블(1번, 부트스트랩), ACM 인증서의 DNS 검증
  레코드(Cloudflare).
- CI apply는 승인 대기 중 main의 `terraform/`이 바뀌면 거부된다(옛 설정으로 최신 상태를 덮지 않도록). 항상 최신
  Terraform run을 승인한다. 코드만 바뀐 머지는 막지 않는다.
