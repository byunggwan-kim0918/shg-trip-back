#!/bin/sh
# 정책마다 재생 DB에서 그 정책 행만 켜고 기록 초안을 다시 짓는다. 운영·공용 로컬 DB는 건드리지 않는다.
# 실행: sh bench/run_replays.sh <jar> base budget_50000_55_40_5 ...
# CATALOG=<폴더>를 주면 장소 카탈로그 끌어오기를 켜고 결과 폴더 이름에 +catalog를 붙인다.
set -e
JAR=$1; shift
cd "$(dirname "$0")/.."
SUFFIX=""
EXTRA=""
if [ -n "$CATALOG" ]; then SUFFIX="+catalog"; EXTRA="--place.catalog.path=$CATALOG"; fi
for policy in "$@"; do
  PYTHONPATH=. python3 bench/replay_places.py activate --policy "$policy"
  (cd .. && java -jar "$JAR" --spring.profiles.active=local,eval \
     --spring.datasource.url=jdbc:postgresql://localhost:5544/trip --spring.datasource.password=replay \
     --eval.mode=trip --eval.drafts=place-pipeline/build/replay/drafts-jp $EXTRA \
     --eval.out=place-pipeline/build/replay/out/"$policy$SUFFIX" > place-pipeline/build/replay/"$policy$SUFFIX".log 2>&1)
  echo "$policy$SUFFIX 끝: $(grep -o '여행 재구성 [0-9]*건' build/replay/"$policy$SUFFIX".log), 끌어온 장소 $(docker exec shg-budget-replay psql -U weShg -d trip -Atc "SELECT count(*) FROM places p JOIN replay_policy b ON b.place_id = p.id AND b.policy = 'base' WHERE p.active AND p.id NOT IN (SELECT place_id FROM replay_policy WHERE policy = '$policy')")"
done
