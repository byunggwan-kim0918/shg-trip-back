#!/usr/bin/env bash
# 운영 시크릿 값을 넣거나 바꾼다.
#
#   terraform/scripts/set-secret.sh fsq                  # FSQ 토큰 (30일마다 갱신)
#   terraform/scripts/set-secret.sh app KAKAO_CLIENT_ID  # shg-trip/prod의 키 하나만 바꾼다
#
# 값은 인자가 아니라 입력으로 받는다 — ps·셸 히스토리에 남지 않게
set -euo pipefail

REGION="${AWS_REGION:-ap-northeast-2}"
APP_SECRET_ID="shg-trip/prod"
FSQ_SECRET_ID="shgtrip/fsq-access-token"

usage() {
  sed -n '4,5p' "$0" >&2
  exit 1
}

read_value() {
  if [ -t 0 ]; then
    IFS= read -rsp "값 입력(표시 안 됨): " value
    echo >&2
  else
    IFS= read -r value
  fi
  [ -n "$value" ] || { echo "빈 값은 넣지 않는다." >&2; exit 1; }
}

put_secret() { # $1=secret id, stdin=secret string
  aws secretsmanager put-secret-value --region "$REGION" --secret-id "$1" \
    --secret-string file:///dev/stdin --query VersionId --output text
}

case "${1:-}" in
  fsq)
    read_value
    printf '%s' "$value" | put_secret "$FSQ_SECRET_ID" >/dev/null
    echo "갱신: $FSQ_SECRET_ID (다음 월간 파이프라인부터 적용)"
    ;;
  app)
    key="${2:-}"
    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] || usage
    command -v jq >/dev/null || { echo "jq가 필요하다." >&2; exit 1; }
    read_value

    # 읽기 실패를 빈 JSON으로 덮으면 다른 키가 전부 사라진다 — 값이 아직 없을 때만 빈 JSON에서 시작
    err=$(mktemp)
    trap 'rm -f "$err"' EXIT
    if ! current=$(aws secretsmanager get-secret-value --region "$REGION" --secret-id "$APP_SECRET_ID" \
        --query SecretString --output text 2>"$err"); then
      grep -q "staging label: AWSCURRENT" "$err" || { cat "$err" >&2; exit 1; }
      current='{}'
    fi

    jq -c --arg k "$key" --arg v "$value" '.[$k] = $v' <<<"$current" | put_secret "$APP_SECRET_ID" >/dev/null
    echo "갱신: $APP_SECRET_ID.$key"
    echo "앱·웹은 시작할 때 읽는다 — 반영하려면 재시작:"
    echo "  aws ecs update-service --cluster shgtrip-cluster --service shgtrip-app --force-new-deployment"
    echo "  aws ecs update-service --cluster shgtrip-cluster --service shgtrip-web --force-new-deployment"
    ;;
  *)
    usage
    ;;
esac
