# 장소 데이터 적재 + 벡터 임베딩 배치 파이프라인

> `SPRING_PROFILES_ACTIVE=batch`로 구동되는 별도 컨테이너(ECS Fargate Scheduled Task)에서 실행.
> 메인 API 서버와 같은 코드베이스를 공유하지만 **런타임은 완전히 분리**되어 있고, `@Scheduled`를
> 쓰지 않는 `CommandLineRunner` 기반 1회성 실행 → 완료 시 컨테이너 종료.

## 전체 흐름

```
BatchJobRunner (CommandLineRunner, @Profile("batch"))
  [1/4] FoursquareSeeder.seed()
        S3FoursquareCsvReader(prod) / 로컬 FoursquareCsvReader(local)
          → foursquare/dt={yyyy-MM-dd}/ 최신 파티션 CSV 읽기
        → 1000행씩 chunk 분할 → @Transactional processChunk()
        → CSV 내부 중복 제거(fsq_place_id) → upsertFoursquarePlace() native upsert
        → PlaceSeedingHistory에 시작/완료/실패 이력 기록
  [1/4-보완] TourApiSeeder.seed()  (batch.tourapi.enabled=true일 때만)
        한국관광공사 TourAPI areaBasedList2 (contentTypeId 12 관광지, 14 문화시설)
        → Foursquare의 빈약한 국내 관광지 커버리지 보완 (실측: 제주 관광지 7곳뿐)
        → areaCode→region 매핑(1:1인 광역시+제주만), name+region 중복 스킵, source='tourapi'
        → 필요 env: BATCH_TOURAPI_ENABLED=true, TOUR_API_SERVICE_KEY(data.go.kr, URL 인코딩된 키),
          BATCH_TOURAPI_AREA_CODES(기본 39=제주)
  [2/4] BatchEnrichScheduler.enrich()  (batch.enrich.enabled=true일 때만)
        ※ 환경 규약: 운영(AWS)은 상시 ON(terraform/ecs.tf batch task에
          BATCH_ENRICH_ENABLED=true 고정) → 임베딩 전 항상 보강. 로컬은 기본값 false →
          enrich 없이 적재(비용·시간 절약). enriched_at IS NULL 게이트라 신규 유입분만 증분 보강.
        enriched_at IS NULL AND active=true 인 장소를 1000건씩 조회
        (BATCH_ENRICH_REGIONS="Jeju,..."로 지역 스코프 가능 — 비우면 전체. 운영은 비워서 전 지역)
        → Anthropic Batch API(Sonnet) 제출 → 폴링(최대 1시간) → 결과 JSON 파싱
        → tags/description/recommended_time_slots 갱신
        → 성공분은 resetEmbeddings()로 embedding=NULL 처리 → 다음 단계에서 재임베딩
          (임베딩 텍스트가 tags/description을 포함하므로 재임베딩 없이는 검색에 미반영)
        ※ recommended_time_slots는 RouteOptimizer.timePreference()가 소비 —
          야간 골프장 같은 시간대 부적합 배치 방지
  [3/4] EmbeddingBatchJob.execute()
        embedding IS NULL AND active=true 인 장소를 100건씩 페이징 조회
        → EmbeddingTextBuilder.buildText() (name+category+tags+description+region 결합)
        → EmbeddingService.embedBatch() (OpenAI text-embedding-3-small, 1536차원)
        → PgVectorPlaceSearchService.storeBatch() (JDBC batchUpdate, ::vector 캐스팅)
        ※ 순서 주의: enrich가 임베딩보다 먼저다 — 같은 실행 안에서 보강 내용이 임베딩에 반영되도록.
```

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
| 설정 | `application-batch.yml` (`batch.foursquare.chunk-size=1000`, `batch.embedding.batch-size=100`, `batch.enrich.chunk-size=1000`, `batch.enrich.enabled` 기본 false) |

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
