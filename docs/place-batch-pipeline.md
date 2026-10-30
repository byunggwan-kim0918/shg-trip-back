# 장소 데이터 적재 + 벡터 임베딩 배치 파이프라인

> `SPRING_PROFILES_ACTIVE=batch`로 구동되는 별도 컨테이너(ECS Fargate Scheduled Task)에서 실행.
> 메인 API 서버와 같은 코드베이스를 공유하지만 **런타임은 완전히 분리**되어 있고, `@Scheduled`를
> 쓰지 않는 `CommandLineRunner` 기반 1회성 실행 → 완료 시 컨테이너 종료.

## 전체 흐름

```
Step Functions (월 1회)
  ExtractFSQ — fsq-extractor/extract.py (Python + DuckDB, 별도 이미지)
        FSQ OS Places Iceberg → 한국 12개 도시 → S3 foursquare/dt={yyyy-MM-dd}/foursquare-places.csv
        · 도시 판정: locality가 도시명이면 그 도시, 비었거나 구·군이면 region, 둘 다 비면 좌표 범위.
          어느 경우든 그 도시 좌표 범위 안이어야 함. region 컬럼은 표준 영어 도시명(Seoul, Jeju…)으로 출력.
          (locality가 자유 텍스트라 표기가 제각각 — 영어 정확일치였던 예전엔 18만 건 중 1.5만, 제주 46건)
        · 사용자 신고(unresolved_flags: closed·doesnt_exist·duplicate…) 있는 행 제외
        · 갱신 기준: 명소·야외 5년, 그 외 2년
        · 프랜차이즈 제외(음식·음료만, 브랜드 목록은 매달 데이터에서 다시 뽑음):
          한글은 "… OO점" 지점명이 20개 이상인 브랜드, 영문은 같은 이름이 20번 이상 반복되는 상호
          (Starbucks·Paris Baguette 등 57개, 12개 도시 5,121행). 영문 업종 일반명(cafe·coffee·bakery…)은 제외.
          한글은 반복 횟수로 판정하지 않는다 — 고향집·서울식당 같은 흔한 상호가 걸린다. 판정 목록은 실행 로그에 남는다
        · Iceberg 스캔엔 단순 조건만 걸고 한국 행을 임시 테이블로 받은 뒤 규칙 적용
          (리스트 컬럼 조건이 파일 프루닝으로 밀리면 iceberg 확장이 내부 오류로 죽는다)
        · 규칙 테스트: fsq-extractor/test_extract.py (합성 테이블)
        · 필요 env: FSQ_ACCESS_TOKEN — Places Portal 개인 토큰, 유효기간 30일. 매달 갱신해 Secrets Manager에 넣어야 함

  RunBatch — BatchJobRunner (CommandLineRunner, @Profile("batch"))
  [1/4] FoursquareSeeder.seed()
        S3FoursquareCsvReader(prod) / 로컬 FoursquareCsvReader(local)
          → foursquare/dt={yyyy-MM-dd}/ 최신 파티션 CSV 읽기
        → 비여행 카테고리 제외(은행·병원·학교·버스정류장·도로·주차장·헬스장·놀이터 등 —
          BLOCKED_CATEGORY_KEYWORDS. 교회·공항·역·마트는 명소/동선 기점이라 남김)
        → 1000행씩 chunk 분할 → @Transactional processChunk()
        → CSV 내부 중복 제거(fsq_place_id) → upsertFoursquarePlace() native upsert
        → PlaceSeedingHistory에 시작/완료/실패 이력 기록
  [1/4-보완] TourApiSeeder.seed()  (batch.tourapi.enabled=true일 때만)
        한국관광공사 TourAPI areaBasedList2 (12 관광지, 14 문화시설, 39 음식점, 32 숙박)
        → 대상: 광역시·제주는 areaCode, 도 안의 도시는 areaCode-sigunguCode (설정 토큰 형식)
        → 조회는 법정동 코드(lDongRegnCd·lDongSignguCd)로 변환해서 한다 — 지역코드 조회는
          areacode가 빈 신규 레코드를 놓친다(제주 관광지 162 vs 557건). 광주만 지역코드 유지
        → 쇼핑(38)은 신분류 SH01 백화점·SH02 몰·SH05 상가·SH06 시장만, 레포츠(28)는 골프 제외
          (수원 31-13, 강릉 32-1, 경주 35-2, 전주 37-12, 여수 38-13) — 기본값이 12개 도시 전부
        → 대상×타입당 최대 1000건, name+region 중복 스킵, source='tourapi', 대표이미지(firstimage) 저장
        → 필요 env: BATCH_TOURAPI_ENABLED=true, TOUR_API_SERVICE_KEY(data.go.kr, URL 인코딩된 키)
  [2/4] WikidataSitelinksJob.run()  (batch.wikidata.enabled=true일 때만 — 운영 ecs.tf에서 켬)
        지역 bbox(장소 좌표 2~98 백분위 + 여유 0.05도)당 SPARQL 1회(언어판 1개 이상 항목만)
        + 항목 300개씩 한국어 별칭·분류(P31) 조회 + 실행당 식음료 분류 목록 1회. 일시 오류는 3회 재시도.
        → 항목이 장소를 고른다(유명한 항목부터, 반경 2km):
          · 장소 이름(괄호·도시명 제거, 띄어쓰기 무시) = 항목 라벨의 앞부분 단어 묶음(한글) 또는 라벨·별칭 전체.
            장소 이름에 항목에 없는 단어가 있으면 안 붙는다 — "창덕궁 후원"·"연세대학교 정문"이 본체를 못 가져간다
          · "A과 B" 꼴 장소(TourAPI "창덕궁과 후원")는 A로도 맞춘다
          · 식음료 분류 항목은 식당 행에만, 나머지는 식당이 아닌 행에만(한식당 "창덕궁"이 궁궐 점수를 못 가져간다)
          · 행정구역 행(States and Municipalities, 동·구·시 등으로 끝나는 Foursquare 행)·버스 정류장은 받지 않는다
          · 같은 이름의 소스별 중복 행에는 카테고리 등급(명소 > 숙소·상점·교통)이 가장 좋은 행 전부에 붙인다
        지역 전체를 0으로 덮고 매칭분만 쓰는 걸 한 트랜잭션으로 매번 전량 재계산. 조회가 하나라도 실패하면 그 지역은 지난 값 유지.
        ※ 보강보다 먼저 돈다 — 보강이 인기도 순으로 처리하려면 필요
  [3/4] BatchEnrichScheduler.enrich()  (batch.enrich.enabled=true일 때만)
        ※ 환경 규약: 운영(AWS)은 상시 ON(terraform/ecs.tf batch task에
          BATCH_ENRICH_ENABLED=true 고정) → 임베딩 전 항상 보강. 로컬은 기본값 false →
          enrich 없이 적재(비용·시간 절약). enriched_at IS NULL 게이트라 신규 유입분만 증분 보강.
        enriched_at IS NULL AND active=true 인 장소를 1000건씩 조회 — 인기도 → 비식당 → id 순,
        이번 실행에서 실패한 id는 쿼리에서 제외. 연속 3개 청크가 전부 실패하면 중단(API 키 오류 등).
        (BATCH_ENRICH_REGIONS="Jeju,..."로 지역 스코프 가능 — 비우면 전체. 운영은 비워서 전 지역)
        → Anthropic Batch API(Haiku 5.5, thinking off, 50% 할인) 제출 → 폴링 → 결과 JSON 파싱
        → tags/description/recommended_time_slots 갱신
        → 식당·카페·술집(카테고리 "Dining and Drinking …")은 DiningEnrichment 규칙:
          업종(식당/카페/베이커리/디저트/술집/간식)을 카테고리로 정해 태그 첫 자리에 넣고, 업종과 어긋나는
          태그(식당의 카페·커피·빵, 카페의 한식·식당)·홍보어·지역명은 코드로 지운다 — 동선 층의
          isMealPlace/isCafeLike가 같은 키워드 목록(isNonMealTag/isRestaurantTag)으로 판정한다.
          총칭("Dining and Drinking > Restaurant")일 때만 모델이 이름을 보고 업종을 고른다.
          설명은 Wikidata에 잡힌 곳(sitelinks > 0)만 받는다(모델이 동네 식당을 모른다). 체류시간은 업종 기본값,
          입장료는 넣지 않는다. 출력은 평균 40토큰대로 관광지 프롬프트(240토큰)의 1/5 수준(입력은 규칙이 길어 더 많다)
        → 성공분은 resetEmbeddings()로 embedding=NULL 처리 → 다음 단계에서 재임베딩
          (임베딩 텍스트가 tags/description을 포함하므로 재임베딩 없이는 검색에 미반영)
        ※ recommended_time_slots는 RouteOptimizer.timePreference()가 소비 —
          야간 골프장 같은 시간대 부적합 배치 방지
  [4/4] EmbeddingBatchJob.execute()
        embedding IS NULL AND active=true 인 장소를 100건씩 페이징 조회
        → EmbeddingTextBuilder.buildText() (name+category+tags+description+region 결합)
        → EmbeddingService.embedBatch() (OpenAI text-embedding-3-small, 1536차원)
        → PgVectorPlaceSearchService.storeBatch() (JDBC batchUpdate, ::vector 캐스팅)
        ※ 순서 주의: enrich가 임베딩보다 먼저다 — 같은 실행 안에서 보강 내용이 임베딩에 반영되도록.
```

### 규모와 비용 (2026-10 실측, 한국 12개 도시)

- Foursquare 추출 약 23.8만(영문 체인 제외 후) → 비여행 카테고리 제외 후 약 18.2만(식음료 약 12.5만), TourAPI 약 4,400(법정동 코드 전환 후 증가)
- 첫 실행 약 $13(Haiku 5.5 Batch 단가 $0.05/$0.25 per MTok 기준, count_tokens 실측):
  식음료 약 $7.4(입력 929~1,239토큰·출력 평균 45토큰 × 12.5만) + 그 외 약 $5.5(입력 약 560·출력 약 240토큰 × 6.2만)
  + 임베딩 $0.3 미만. 이후 매달 신규분만 처리해 $2 미만. 보강은 1,000건씩 순차라 첫 실행은 하루 이상 걸릴 수 있다

## 핵심 파일

| 역할 | 파일 |
|---|---|
| 진입점 | `domain/place/batch/BatchJobRunner.java` |
| CSV 시딩 | `domain/place/batch/FoursquareSeeder.java` |
| TourAPI 관광지 시딩 | `domain/place/batch/TourApiSeeder.java` (관광공사 공공데이터, 관광지 커버리지 보완) |
| CSV 소스 추상화 | `domain/place/s3/FoursquareCsvSource.java`, `S3FoursquareCsvReader.java`(prod), 로컬 구현체(local) |
| 시딩 이력 | `domain/place/batch/PlaceSeedingHistory.java` (+ Repository) |
| 임베딩 생성 배치 | `domain/place/batch/EmbeddingBatchJob.java` |
| 임베딩 생성 추상화 | `domain/place/embedding/EmbeddingService.java` (interface) → `OpenAIEmbeddingService.java` (구현체) |
| 임베딩 텍스트 결합 | `domain/place/embedding/EmbeddingTextBuilder.java` |
| 벡터 저장/검색 추상화 | `domain/place/vector/PlaceVectorSearchService.java` (interface) → `PgVectorPlaceSearchService.java` (구현체, JdbcTemplate + pgvector `<=>`) |
| 태그/설명 보강 | `domain/place/batch/BatchEnrichScheduler.java` (Anthropic Batch API 직접 HTTP 호출, SDK 미사용) |
| 마이그레이션 | `db/migration/V19__add_embedding_columns.sql` (embedding 컬럼+HNSW 인덱스), `V23__create_place_seeding_history.sql`, `V24__remove_places_unique_constraint.sql` |
| 인기도 매칭 | `domain/place/batch/WikidataSitelinksJob.java` (Wikidata SPARQL, CC0·무인증) |
| FSQ 추출 | `fsq-extractor/extract.py` (+ `test_extract.py`) |
| 설정 | `application-batch.yml` (`batch.foursquare.chunk-size=1000`, `batch.embedding.batch-size=100`, `batch.enrich.chunk-size=1000`, `batch.enrich.enabled` 기본 false, `anthropic.batch.model=claude-haiku-5-5`) |

## 설계 특징 (검증됨)

- **API 호출과 DB 트랜잭션이 분리되어 있음**: `EmbeddingBatchJob.processBatch()`는 `embedBatch()`(외부 HTTP, 트랜잭션 없음) 호출 후 별도로 `storeBatch()`(`@Transactional`, DB-only batchUpdate)를 호출 — 외부 API 응답 대기 중 DB 커넥션을 잡고 있지 않음.
- **부분 실패 처리는 두 배치가 다름 (2026-07 코드 기준)**:
  - `BatchEnrichScheduler`는 **건별로 처리**한다. `processResults()`가 JSONL 결과를 한 줄씩 파싱해 성공 건은 즉시 `placeRepository.save(place)`로 커밋하고, 실패 건만 `failedIds`에 담아 잔존시킨다. 청크 전체를 버리는 경우는 **제출/폴링/결과조회 HTTP 호출 자체가 실패**해 성공분이 애초에 없는 경우(`ChunkResult(0, size, allIds)`)로 한정된다. 제출·폴링은 `MAX_RETRIES=3` 지수 백오프도 갖췄다.
  - `EmbeddingBatchJob`은 **텍스트 생성 실패는 개별적으로 건너뛰지만**(`textBuildFailedIds`), OpenAI `embedBatch()` API 호출 자체가 (내부 재시도 3회 후에도) 실패하거나 응답 수가 불일치하면 그 배치(≤100건)를 통째로 실패 처리한다. 다음 실행 시 `embedding IS NULL` 조건으로 자동 재시도되므로 **데이터 누락은 없다**. 임베딩 API(text-embedding-3-small)는 단가가 매우 낮아 재요청 비용 영향도 미미하다.
- **동시 실행 보호는 인프라(ECS 스케줄) 레벨에만 의존**: 코드 레벨 분산 락은 없음. `upsertFoursquarePlace`(upsert)와 `embedding IS NULL` 조건 필터 덕분에 두 잡이 겹쳐도 데이터가 깨지진 않지만(idempotent), API 호출이 중복돼 비용이 늘 수 있음. ECS 스케줄이 이전 태스크 종료 전에 재실행되지 않도록 보장하는지는 인프라 설정 확인 필요.
- **SQL Injection 없음**: `PgVectorPlaceSearchService`가 `StringBuilder`로 SQL을 만들지만 모든 가변 값은 `?` 플레이스홀더 + `params.toArray()`로 바인딩됨. `upsertFoursquarePlace`도 `@Param` 네임드 파라미터 사용.
- **인터페이스가 구현체 1개뿐**: `EmbeddingService`/`PlaceVectorSearchService` 모두 구현체가 하나(`OpenAIEmbeddingService`, `PgVectorPlaceSearchService`). 주석에 "향후 Qdrant 등으로 전환 시 구현체만 교체" 의도가 적혀 있음 — 현재 규모에서는 오버엔지니어링일 수 있으나, 외부 API 의존성을 테스트에서 모킹하기 쉽다는 실익은 있음.

## 알려진 개선 후보 (아직 미반영, 우선순위 낮음~중간)

1. `EmbeddingBatchJob`의 `embedBatch()` API 실패 시 청크(≤100건)를 통째로 버리지 말고, 청크를 반으로 재분할해 재시도하거나 정상 응답분만 부분 저장 (재요청 비용 절감). 단, 임베딩 API 단가가 낮아 이득이 작고 회귀 위험이 있어 우선순위 낮음.
2. HNSW 인덱스 파라미터(`m=16, ef_construction=64`, pgvector 기본값)는 장소 데이터가 수십만 건 이상으로 커지면 재튜닝 필요 — 현재 규모에서는 문제 없음.
3. `BatchEnrichScheduler`/`OpenAIEmbeddingService`가 각각 자체 `HttpClient` + 재시도 로직을 들고 있어 코드 중복 — 공통 HTTP 재시도 유틸로 추출 가능.

---
_관련 문서: [`CLAUDE.md`](../../CLAUDE.md) (전체 프로젝트 개요), [`SERVICE_SPECIFICATION.md`](../../SERVICE_SPECIFICATION.md)_
