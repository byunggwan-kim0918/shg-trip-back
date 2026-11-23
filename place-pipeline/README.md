# place-pipeline

공급처별 장소 원본을 받아 공통 후보로 바꾸고, 적재 적합성과 출처 간 같은 장소를 판정한다. 운영 DB에는 쓰지 않는다.
월간 운영 추출은 아직 `fsq-extractor`가 맡고, 이 모듈은 공급처 비교·여행지 후보 사전·전 세계 확장 준비에 쓴다.

```
L0 원본 스냅샷   build/<out>/raw/<source>/<범위>.parquet + .manifest.json (릴리스·취득 시각·sha256·라이선스·행 수·전송량·메모리)
L1 공통 후보     candidates/<범위>.parquet  (이름 목록·좌표·FSQ 라벨 체계 카테고리·갱신일·폐업·브랜드·출처 링크·라이선스)
L2 출처 간 연결  links/<범위>.parquet  (source_id: Overture가 든 FSQ ID / exact_name: 정확 키 + 50m)
L3 적재 적합성   candidates.exclusion (no_name·bad_coordinate·closed·flagged·unmapped_category·non_travel_category·stale·junk_name·chain)
```

## 실행

```bash
cd place-pipeline
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt pytest
.venv/bin/python -m pytest -q tests

# 표본 범위 비교(Overture는 계정 없이 공개 S3, FSQ는 FSQ_ACCESS_TOKEN 필요)
FSQ_ACCESS_TOKEN=... .venv/bin/python -m pipeline.cli pilot --areas pilot/areas.tsv --landmarks pilot/landmarks.tsv \
  --out build/pilot --sources overture,fsq
# 이미 받은 원본으로 리포트만 다시 만들기
.venv/bin/python -m pipeline.cli pilot --areas pilot/areas.tsv --landmarks pilot/landmarks.tsv --out build/pilot --reuse

# GeoNames 국가 덤프(https://download.geonames.org/export/dump/CC.zip) → 여행지 후보 CSV
.venv/bin/python -m pipeline.cli destinations --geonames build/geonames/JP.txt build/geonames/KR.txt \
  --out build/destinations-candidates.csv
```

후보 CSV는 배치의 `DestinationCatalogSync`가 `batch.destinations.candidates-file`로 읽어 `destinations`에 CANDIDATE로 넣는다.

## 규칙이 사는 곳

| 규칙 | 파일 |
|---|---|
| 이름 정확 키 | `pipeline/names.py` (계약 `src/test/resources/place-names/exact-keys.tsv`, Java `PlaceNames`·DB `place_name_key`와 같다) |
| Overture 분류 → FSQ 라벨 | `pipeline/overture_categories.csv` (구체적인 항목부터 거슬러 올라가 처음 맞는 줄) |
| 적재 적합성 | `pipeline/policy.py` (인기도·전화·웹사이트·LLM 점수로는 빼지 않는다) |
| 같은 장소 자동 연결 | `pipeline/identity.py` (ID 연결, 정확 키 + 50m. 이름 변형은 배치 PlaceLinkJob 몫) |
| 여행지 후보 | `pipeline/geonames.py` (인구로 거르지 않는다. 개별 장소·시가지 일부는 넣지 않는다) |

## 주의

- Overture를 공개 S3에서 bbox로 바로 질의하면 범위마다 전 파일의 메타데이터를 읽는다. 같은 연결 안에서는 캐시되지만, 국가 단위 이상은 릴리스당 한 번 사본을 만들어 쓴다.
- Overture 행의 FSQ 출처는 FSQ와 독립된 증거가 아니다. 두 출처에 다 있다고 실존 근거가 둘이 되지 않는다.
- OSM·국가 관광청 데이터는 이 모듈에 아직 없다. OSM(ODbL)은 결합 방식 검토 전까지 운영 데이터에 넣지 않는다.
- 전 세계 원본 전체 다운로드는 승인 뒤에만 돌린다. 표본(국가 1~2개, 소형 bbox)의 manifest로 전송량·시간·메모리를 먼저 잰다.
