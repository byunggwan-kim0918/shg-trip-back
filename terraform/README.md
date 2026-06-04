# Terraform 인프라

## 최초 세팅 순서

### 1. tfstate 백엔드 버킷 수동 생성 (1회)
```bash
aws s3 mb s3://shgtrip-terraform-state --region ap-northeast-2
aws s3api put-bucket-versioning \
  --bucket shgtrip-terraform-state \
  --versioning-configuration Status=Enabled
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
```

### 3. Terraform 실행
```bash
terraform init
terraform plan
terraform apply
```

### 4. ECR에 이미지 푸시

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

- `terraform.tfvars`는 절대 git에 커밋하지 말 것 (.gitignore 확인)
- FSQ 토큰은 Secrets Manager에 저장되므로 코드에 노출되지 않음
- vpc_subnet_ids, vpc_security_group_ids는 기존 VPC 설정 확인 후 입력
