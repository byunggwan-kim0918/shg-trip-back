#!/bin/bash
set -e

echo "LocalStack 초기화 시작..."

# S3 버킷 생성
echo "S3 버킷 생성 중..."
awslocal s3 mb s3://shgtrip-data || echo "S3 버킷이 이미 존재합니다."

# Secrets Manager 시크릿 생성 (로컬 개발용)
echo "Secrets Manager 시크릿 생성 중..."
awslocal secretsmanager create-secret \
  --name shg-trip/local \
  --secret-string '{
    "DB_PASSWORD": "shg99**",
    "JWT_SECRET": "bG9jYWxfZGV2X3NlY3JldF9rZXlfZm9yX2p3dA==",
    "ANTHROPIC_API_KEY": "sk-ant-local-test",
    "GOOGLE_PLACES_API_KEY": "test-key"
  }' 2>/dev/null || echo "시크릿이 이미 존재합니다."

echo "LocalStack 초기화 완료!"
