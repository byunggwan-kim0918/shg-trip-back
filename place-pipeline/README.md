# place-pipeline

공급처별 장소 원본을 받아 공통 후보로 바꾸고, 적재 적합성과 출처 간 같은 장소를 판정한다. 운영 DB에는 쓰지 않는다.
월간 운영 추출은 아직 `fsq-extractor`가 맡고, 이 모듈은 공급처 비교·여행지 후보 사전·전 세계 확장 준비에 쓴다.

```
L0 원본 스냅샷   build/<out>/raw/<source>/<범위>.parquet + .manifest.json (릴리스·취득 시각·sha256·라이선스·행 수·전송량·메모리)
L1 공통 후보     candidates/<범위>.parquet  (이름 목록·좌표·FSQ 라벨 체계 카테고리·갱신일·폐업·브랜드·출처 링크·라이선스)
L2 출처 간 연결  links/<범위>.parquet  (자동: source_id·exact_name·phone_name·name_contains / 검토: phone·대분류 다른 exact_name)
L3 적재 적합성   candidates.exclusion (no_name·bad_coordinate·closed·flagged·unmapped_category·non_travel_category·stale·junk_name)
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
| 한국어 표시 이름 검사 | `pipeline/names.py` `korean_label` (계약 `src/test/resources/place-names/korean-labels.tsv`, Java `OverseasEnrichment.nameKoText`와 같다. Wikidata 한국어 라벨은 끝의 구분 괄호를 뗀 뒤 같은 검사를 거친다 `wikidata_korean_label`) |
| Overture 분류 → FSQ 라벨 | `pipeline/overture_categories.csv` (구체적인 항목부터 거슬러 올라가 처음 맞는 줄) |
| 적재 적합성 | `pipeline/policy.py` (인기도·전화·웹사이트·LLM 점수로는 빼지 않는다) |
| 이름 규칙 | `pipeline/name_rules.tsv` (한 줄에 정규식 하나, `region`은 `*` 공통 또는 국가 코드. 제외·검토·명소 근거·부속 표지·체인 지점명·연결 일반명사) |
| 같은 장소 연결 | `pipeline/identity.py` (ID·정확 키·고유 전화번호·식음료 이름 포함, FSQ 단독 판정. 표기 변형·번역은 배치 PlaceLinkJob 몫) |
| Wikidata 연결 | `pipeline/identity.py` `wikidata_links` (라벨·현지어 라벨·별칭 정확 키 → 이름 머리말이 항목 이름인 포함 연결. ` - `·쉼표 꼬리는 지명·군말·같은 항목 이름일 때만 뗀다. 브랜드·체인 항목은 잇지 않고, 지명 항목·지명 이름은 가게에 잇지 않고, 가게·숙소는 사업체가 아닌 먼 항목에 잇지 않고, 흔한 이름의 먼 행은 잇지 않는다) · 남은 말 규칙 `name_rules.tsv` same_kind·tail_filler·place_preposition·operator_prefix · 전후 비교 `bench/wikidata_audit.py` · 나라별 표본 비교 `bench/global_identity_check.py` |
| 후보 풀·증거 | `pipeline/evidence.py` (Overture + FSQ 단독, FSQ 폐업·연수·독립 확인, 체인 근거별 표시, 웹사이트 종류. 점수는 매기지 않는다) · 호스트 분류 `pipeline/url_hosts.tsv` |
| 역할·판정 | `pipeline/selection.py` (역할 5종 × 추천·상황 활용·검토·제외, LLM 없음) · 역할 표 `pipeline/roles.tsv` |
| 풀 안 같은 장소 | `pipeline/dedup.py` (SAME·PART_OF·DISTINCT·REVIEW, SAME만 병합, 원본 행 보존·run_id로 되돌림) |
| Wikidata 스냅샷 | `pipeline/wikidata.py` (좌표 상자 SPARQL, 칸 4등분, 초당 1회. ja·ko·en 라벨 + 그 나라 언어 라벨·별칭(`pipeline/country_languages.tsv`). 지명 항목(거주지·행정 구역·도시·읍·마을, 건축물 제외) 판정 `area_qids`, 브랜드·체인 항목 판정 `brand_qids`, 사업체(숙소·식당·가게·회사) 판정 `business_qids`, 종류별 항목(`pipeline/item_kinds.tsv`, 휴게소) 판정 `kind_qids`. 모두 연결 후보가 될 수 있는 항목 전부에 대해 받는다) |
| OSM 추출 | `pipeline/osm.py` (.osm.pbf → 이름·wikidata 있는 가게·명소·자연·폭포, 면은 꼭짓점 표본 평균, 관계(습원·국립공원·등산로)는 구성원 표본 평균과 범위 extent_m) |
| OSM 연결·보강 | `pipeline/osm_link.py` (풀 행에 SAME만 연결, 넓은 면·관계는 범위 안 같은 이름, 위도 띠 처리) · 태그 → 풀 분류 라벨 `pipeline/osm_roles.tsv`(역할은 `roles.tsv` 한 곳에서) · 결과 표는 ODbL 출처 열을 달고 운영 DB와 분리 |
| OSM 단독 장소(실험) | `pipeline/osm_places.py` (풀과 어떤 관계도 없는 OSM 장소를 풀 모양 행으로, 선별에서는 항상 지명도로 가른다) |
| 위키보야주 목록 | `pipeline/wikivoyage.py` (덤프 → see·eat·vCard 목록, 풀 연결은 wikidata·가까운 같은 이름) · 틀·절 제목 `pipeline/wikivoyage_sections.tsv`(언어판별) |
| 저장 정책 | `pipeline/storage_policy.py` (등급·역할별로 담을 행을 고른다. 같은 격자·종류가 몰린 곳만 체감 몫 `max(floor, n^지수)`으로 줄이고 장소 수 상한은 두지 않는다) |
| 정책 비교 지표 | `pipeline/metrics.py` (저장 용량·비용, 위키데이터 항목 기준 유명 보존, 숨은 장소, 격자 커버리지·음식 종류, 일정 초안 장소 보존·주변 식사 선택지, 소도시 선택지) |
| 정책 비교 실험 | `bench/select_policies.py` (정책 여러 개를 같은 지표로 비교, LLM 호출 없음) · 초안 장소 정답 표 `eval/jp-draft-places.tsv` |
| 저장 용량 실측 | `bench/pg_storage_bench.py` (운영과 같은 메모리 4GB pgvector 컨테이너에서 장소 수별 표·색인·HNSW 크기, 생성 시간, 검색 지연) |
| 예산 정책 | `pipeline/budget_policy.py` (장소 수 상한이 있을 때 한계 가치 순으로 고른다. 유명 명소 먼저, 몫은 역할 묶음에만 두고 나라별 몫은 없다) |
| 예산 정책 실험 | `bench/budget_policies.py` (상한별로 기준 선별과 같은 지표 비교) · 카탈로그 조회 실측 `bench/parquet_tail_bench.py` |
| 좌표 틀린 동명 행 | `pipeline/mislocated.py` (유명 항목과 이름이 같은데 1.5km 넘게 떨어진 행 가운데, 주소 동네가 제자리 행과 같은 것만 중복으로 뺀다) |
| 한국어 표기 규칙 | `pipeline/korean_names.py` (가나·헵번식 로마자 → 한글, 신사·공원 같은 일반어만 번역. 한자 읽기는 Wikidata 가나 이름(P1814)·로마자 라벨이 있을 때만) |
| 연결·병합 다시 적용 | `bench/identity_v3.py` (v2 나라·예산 DB 사본에 Wikidata 연결·dedup만 다시 적용, 선별은 그대로) → `bench/sync_core.py` (실험 DB core 행 QID·이름·합침·본체 넣기·원천 id 매핑, 백업·dry-run, `--write-sql` 운영 적용 스크립트) · 원천 id 매핑 `bench/source_refs.py`(DB 행 ↔ Overture GERS id·FSQ id, 운영 복사본에도 같은 방법) · 운영 사전 점검 `bench/sql/sync_preflight.sql` |
| 전 국가 표본 검증 | `bench/global_identity_check.py` (범위 `pilot/identity-areas.tsv`, 두 코드 실행 결과 비교) |
| 일정 재생 | `bench/replay_places.py`(별도 컨테이너 적재·정책 전환·카탈로그 Parquet 내보내기) · `bench/replay_embed.py` · `bench/run_replays.sh` · `bench/replay_score.py` |
| 여행지 후보 | `pipeline/geonames.py` (인구로 거르지 않는다. 개별 장소·시가지 일부는 넣지 않는다) |

## 주의

- Overture를 공개 S3에서 bbox로 바로 질의하면 범위마다 전 파일의 메타데이터를 읽는다. 같은 연결 안에서는 캐시되지만, 국가 단위 이상은 릴리스당 한 번 사본을 만들어 쓴다.
- Overture 행의 FSQ 출처는 FSQ와 독립된 증거가 아니다. 두 출처에 다 있다고 실존 근거가 둘이 되지 않는다.
- OSM(ODbL)에서 온 값은 `osm_evidence`·`place_names(origin=osm)`·`osm_places`에만 있고, 결합 방식이 정해지기 전까지 운영 DB에 넣지 않는다. OSMF 지침은 다른 데이터셋과 OSM을 맞춰 중복 제거·보완한 경우를 공유 조건 예외(Collective Database)로 다루지 않는다.
- 위키보야주(CC BY-SA)는 실렸는지만 근거로 쓰고 본문은 옮기지 않는다.
- 전 세계 원본 전체 다운로드는 승인 뒤에만 돌린다. 표본(국가 1~2개, 소형 bbox)의 manifest로 전송량·시간·메모리를 먼저 잰다.
