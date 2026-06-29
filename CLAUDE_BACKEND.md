# ☕ CLAUDE_BACKEND.md - Spring Boot 백엔드

> 백엔드 작업 시 적용되는 규칙. 공통 규칙은 루트 `CLAUDE.md` 참조.

---

## 📌 기술 스택 (실제)

```yaml
Language: Java 21
Framework: Spring Boot 3.5.9
Build: Gradle (Groovy DSL) - build.gradle
DB: PostgreSQL 16.11 (Docker, pgvector 포함)
Cache: Redis 7.4-alpine (Docker)
ORM: Spring Data JPA
Migration: Flyway (V1~V24)
Auth: JWT (jjwt 0.12.6) + OAuth 2.0 (Google/Kakao/Naver, Strategy Pattern)
Security: Spring Security 6 (STATELESS)
HTTP Client: Spring RestClient (WebClient 아님)
AI: Anthropic Java SDK 2.15.0 (Claude Haiku 4.5 / Sonnet 4.6)
Embedding: OpenAI text-embedding-3-small (1536차원)
Cloud: Spring Cloud AWS 3.3.0 (S3, Secrets Manager / 로컬은 LocalStack)
Lombok: 사용 중
Retry: spring-retry
Test: JUnit 5 + Mockito + jqwik(Property-based) — Testcontainers 미사용(실 DB/Redis 의존)
Coverage: JaCoCo 0.8.12 (70% 최소, dto/entity/config/global 제외)
```

---

## 🏗️ 프로젝트 구조 (domain별)

```
src/main/java/com/shg/trip/shgtrip/
├── domain/
│   ├── auth/        # OAuthController, AuthService + oauth 전략(Google/Kakao/Naver Strategy + Factory)
│   │                 # entity: OAuthProvider, RefreshToken, UserAuthProvider
│   ├── user/        # UserController(me/profile), UserService
│   ├── place/       # PlaceController, WishlistController
│   │   ├── batch/    # Foursquare 시딩 + 임베딩 배치 (place-batch-pipeline.md 참고)
│   │   ├── client/   # GooglePlacesClient
│   │   ├── embedding/ # OpenAI 임베딩 호출
│   │   ├── s3/        # CSV/이미지 S3 연동
│   │   └── vector/    # pgvector 유사도 검색
│   ├── itinerary/   # ItineraryController, SharedItineraryController(비인증)
│   │                 # entity: Itinerary(@Version, soft delete), ItineraryStep, AlternativeOption
│   └── planning/    # AI 일정 생성 파이프라인 — 아래 별도 섹션
│
└── global/
    ├── config/      # 13개 설정 클래스 — 아래 표 참고
    ├── entity/      # BaseTimeEntity
    ├── exception/   # BusinessException, ErrorCode, GlobalExceptionHandler
    ├── response/    # ApiResponse<T>, ErrorInfo, PageResponse<T> (전부 record)
    ├── security/    # JwtAuthenticationFilter, JwtTokenProvider, JwtProperties, UserPrincipal
    ├── util/        # GeoUtils
    └── validation/  # DateRangeValidator, @ValidDateRange
```

### global/config 클래스별 역할

| 클래스 | 역할 |
|---|---|
| `AnthropicClientConfig` | `AnthropicClient` Bean |
| `AnthropicProperties` | haiku/sonnet 모델명, max-output-tokens (record) |
| `AsyncConfig` | `planningExecutor`, `googleSyncExecutor` 비동기 스레드풀 |
| `CookieProperties` | secure 플래그 등 (record) |
| `JpaConfig` | `DateTimeProvider` Bean (Auditing) |
| `OAuthProperties` | provider별 client-id/secret (record) |
| `OpenAIProperties` | 임베딩 모델/차원 설정 (record) |
| `PlanningProperties` | soft-validation threshold (record) |
| `RedisConfig` | Redis 연결/직렬화 |
| `RestClientConfig` | `RestClient` Bean |
| `S3Config` / `S3Properties` | S3Client Bean / 버킷 설정 (record) |
| `SecurityConfig` | `SecurityFilterChain` Bean — `/api/auth/**`, `/actuator/health`, `/api/shared/**` permitAll |

---

## 🔐 인증 구조

```
OAuth Flow:
  프론트 → 소셜 로그인 → 인가 코드 → POST /api/auth/oauth/callback
  → AuthService → OAuthStrategyFactory → Google/Kakao/NaverOAuthStrategy
  → 신규 사용자 자동 가입 (User + UserAuthProvider)
  → JWT Access Token (30분) + Refresh Token (7일, httpOnly 쿠키, Redis 저장)

Token Refresh:
  POST /api/auth/refresh (쿠키의 refresh_token 사용) → rotation(기존 삭제 + 신규 발급)
```

---

## 🤖 AI 일정 생성 파이프라인 (domain/planning)

**핵심 설계: 순서/시간/동선 결정권은 백엔드 결정론적 코드가 갖고, LLM은 의도(concept)와
표현(story)만 담당한다.** 과거에는 Sonnet 1회 호출이 day별 장소+순서까지 전부 결정했으나,
pace 미준수·동선 점프 문제로 재설계됨.

### 정상 경로 — OptimizedGenerationExecutor (@Async, 별도 빈)

```
[10%] enrichInput (Haiku, OptimizedClaudeAIService) — 입력 보강
[30%] VectorSearchQueryService — pgvector 유사도 검색으로 카테고리별 PlaceCandidate 조회
      → FallbackDecider 판단 (accommodation≥1, restaurant≥days×2, attraction≥days, 총≥15 미달 시 fallback)
[40%] Google Places 동기화 (best-effort, 20초 타임아웃, stale/foursquare 소스만 갱신)
[50%] SelectionCallGenerator.selectPlaces() (Sonnet, select-places.txt)
      → 출력: concept + day별 장소 인덱스(힌트) + pairs(인접 강제) + highlight/restIndices
      → 시간/교통/비용은 출력하지 않음 (토큰 절감 + 책임 분리)
RouteOptimizer.repairAndSchedule() — LLM 재호출 없는 100% 결정론적 코드
      1. fixpoint 루프(최대 5회): repairPaceQuota → repairPairs → repairDistanceOutliers
      2. 루프 종료 후 무조건 한 번 더 repairPaceQuota (안전망)
      3. repairHubs / repairAccommodationContinuity / repairClosedDayPlaces
      4. orderDay: NN + 2-opt (pair는 union-find로 묶어 최적화), anchor 기반 경로 방향 고정
      5. scheduleDay: 식사 슬롯 고정, 교통은 Haversine 추정, notes(story)는 비워둠
hardValidator.validate() — 안전망(결정론적이라 실패해도 재시도 없이 로그만)
[90%] ItinerarySaveHelper.save(..., alreadyOptimized=true) (@Transactional, 별도 빈)
      → ItineraryDataMapper가 autoFixer/routeOptimizer.optimize() 스킵 (fallback 전용 레거시)
[100%] SSE "complete" emit (emitter는 안 닫음 — story 대기)
→ [비동기, critical path 밖] StoryGenerationService.generateAndAttach (@Async, 별도 빈)
      AssemblyCallGenerator.assembleItinerary() (Haiku, assemble-itinerary.txt)
      → 구조 필드 없는 스키마, {title, tags, steps[{stepOrder, story}]}만 출력
      → updateNotesByItineraryIdAndStepOrder (@Modifying + @Transactional 필수,
        Itinerary @Version은 건드리지 않아 동시 PUT 편집과 충돌 안 함)
      → "story-ready"/"story-failed" emit 후 emitter.complete()
```

### Fallback 경로 — ItineraryGenerationExecutor (후보 절대부족 시)

```
[20%] enrichInput (Haiku, ClaudeAIService)
[50%] generateItinerary (Sonnet, Tool Use 전체 스키마 — 구조 전체를 1회로 생성)
[70%] ItineraryValidationService.validateWithRetry()
      Hard 검증(Java, 1회) + Soft 검증 루프(최대 3회, threshold 70점)
      실패 → enhanceItinerary(Sonnet) → 3회 실패 → regenerateItinerary(Sonnet)
[90%] ItinerarySaveHelper.save() (@Transactional)
[100%] SSE complete
```

### 핵심 설계 원칙

| 항목 | 내용 |
|---|---|
| 동시 작업 제한 | 유저당 1개, 새 요청 시 기존 작업 자동 취소 (CancellationRegistry) |
| SSE | 10분 타임아웃, complete 후에도 story 대기 위해 안 닫음, "story-ready"에서 닫음 |
| @Async 분리 | `StoryGenerationService`/`ItinerarySaveHelper`를 별도 빈으로 분리 — 같은 클래스 내부 호출은 self-invocation으로 `@Async`가 안 걸림 |
| LLM 호출 횟수 | 정상 경로: Haiku 1회(enrich) + Sonnet 1회(selection) + Haiku 1회(비동기 story) / Fallback: Haiku 1회 + Sonnet 최대 ~8회 |
| `@Modifying` JPQL | `@Transactional` 누락 시 `TransactionRequiredException` (Spring Data가 자동으로 트랜잭션을 걸어주지 않음) |

### 주요 클래스

- `SelectionCallGenerator` / `SelectionToolSchema` — Sonnet 1차 호출 (concept + pool)
- `RouteOptimizer` — 결정론적 day/순서/시간/동선 확정
- `AssemblyCallGenerator` / `AssemblyToolSchema` — Haiku story 채우기
- `StoryGenerationService` — 비동기 story 파이프라인
- `FallbackDecider` — 후보 충분성 판단
- `HardValidator` / `ItineraryValidationService` — Hard/Soft 검증
- `ItineraryAutoFixer` — fallback 경로 전용 구조 교정 (정상 경로에선 스킵)
- `ItineraryDataMapper` — AI 응답 → 엔티티 변환 + Google Places 배치 조회
- `VectorSearchQueryService` — pgvector 기반 후보 검색
- `GenerationResultStore` / `TravelPlannerService` — jobId/결과/SSE emitter 관리

### 모델 설정 (application-local.yml)

```yaml
anthropic.models.haiku: claude-haiku-4-5-20251001
anthropic.models.sonnet: claude-sonnet-4-6
anthropic.max-output-tokens: 64000
openai.embedding-model: text-embedding-3-small (1536차원)
planning.validation.soft-threshold: 70
```

### 프롬프트 (src/main/resources/prompts/)

`select-places.txt`, `assemble-itinerary.txt`, `enrich-input.txt`, `generate-itinerary.txt`,
`enhance-itinerary.txt`, `regenerate-itinerary.txt`, `validate-soft.txt`

---

## 📡 API 엔드포인트 (실제, grep 검증)

```
# 인증
POST   /api/auth/oauth/callback
POST   /api/auth/refresh
POST   /api/auth/logout

# 사용자
GET    /api/users/me
PATCH  /api/users/profile

# 장소 / 찜
GET    /api/places/{id}
GET    /api/places/search
GET    /api/wishlist
POST   /api/wishlist/{placeId}
DELETE /api/wishlist/{placeId}

# 일정 생성 (SSE)
POST   /api/itineraries/generate
GET    /api/itineraries/generate/{jobId}/stream
GET    /api/itineraries/generate/{jobId}/result

# 일정 관리
GET    /api/itineraries
GET    /api/itineraries/{id}
PUT    /api/itineraries/{id}
DELETE /api/itineraries/{id}
POST   /api/itineraries/{id}/finalize
POST   /api/itineraries/{id}/share
PATCH  /api/itineraries/{id}/steps/{stepId}/select-alternative

# 공유 (비인증)
GET    /api/shared/{token}

# 헬스체크
GET    /actuator/health
```

---

## 📝 코드 작성 규칙

### API 응답 포맷 (통일)
```java
// 성공
ApiResponse.success(data)  // { success: true, data: {...}, error: null }

// 실패
ApiResponse.error(ErrorCode.USER_NOT_FOUND)  // { success: false, data: null, error: { code, message } }
```

### Controller 패턴
```java
@RestController
@RequestMapping("/api/...")
@RequiredArgsConstructor
public class XxxController {
    private final XxxService xxxService;

    @GetMapping
    public ResponseEntity<ApiResponse<XxxResponse>> getXxx(
            @AuthenticationPrincipal UserPrincipal user) {
        return ResponseEntity.ok(ApiResponse.success(xxxService.getXxx(user.getUserId())));
    }
}
```

### Service 패턴
```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class XxxService {
    // 조회는 readOnly, 변경은 @Transactional 메서드 레벨
}
```

### Entity 패턴
```java
@Entity @Table(name = "xxx")
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Xxx extends BaseTimeEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // Builder 패턴 사용
}
```

### 예외 처리
```java
// 비즈니스 예외는 BusinessException + ErrorCode
throw new BusinessException(ErrorCode.USER_NOT_FOUND);
// GlobalExceptionHandler가 자동 처리
```

### @Async + @Transactional 분리 패턴
`@Async`가 걸린 메서드 내부에서 같은 클래스의 `@Transactional` 메서드를 호출하면 self-invocation으로
프록시가 적용되지 않는다. `ItinerarySaveHelper`, `StoryGenerationService`처럼 **별도 빈으로 분리**할 것.

---

## 📊 성능 고려사항

- N+1 쿼리 방지 (Fetch Join, EntityGraph) — `ItineraryRepository.findByIdWithDetails`
- 페이지네이션 필수 (목록 API, `PageResponse<T>`)
- Redis 캐시 활용 (Refresh Token, 장소 데이터 등)
- 트랜잭션 범위 최소화
- pgvector 유사도 검색으로 LLM 호출 없이 후보군 확보 (토큰/비용 절감)

---

## 🧪 테스트 규칙

- **`@Tag("integration")`** — `ShgTripBackApplicationTests` 등 DB/Redis 필요한 테스트 (docker compose up -d 필수)
- 태그 없음 — 단위 테스트 (Mockito 모킹)
- **jqwik Property-based 테스트** 다수 사용 중 (`FallbackDeciderPropertyTest`,
  `PlaceFreshnessFilterPropertyTest`, `VectorSearchFilterConsistencyPropertyTest` 등)
- **Testcontainers 미사용** — 실제 로컬 Docker DB/Redis에 의존 (docker-compose.yml)
- JaCoCo 커버리지 70% 이상 유지 (제외: DTO, Entity, Config, Global)
  - `./gradlew jacocoTestReport` → `build/reports/jacoco/test/html/index.html`

```bash
./gradlew test                                    # 단위 테스트만
./gradlew integrationTest                         # 통합 테스트만 (DB/Redis 필요)
./gradlew test integrationTest                    # 전체
./gradlew test --tests "com.shg.trip.shgtrip.domain.auth.UserServiceTest"
```

---

## 🐛 실제로 겪은 버그 (재발 방지용)

1. **`@Modifying` JPQL에 `@Transactional` 빠뜨림** → `@Async` 호출부엔 트랜잭션이 없어
   `TransactionRequiredException`. Spring Data가 자동으로 걸어주지 않음.
2. **`findFirst()`로 허브/대표 후보 선택 시 오선택** — Foursquare 데이터에 본체 외 하위 POI가
   섞여있어, 평점(rating) 기준 정렬 필요.
3. **`priceLevel` null → 무조건 0원 처리 금지** — Google Places가 가격 정보를 안 주는 경우가
   흔함(특히 소규모 식당). 카테고리 기본값 필요.
4. **거리 검증이 일부 경로에만 있던 버그** — DB 캐시 경로/신규 조회 경로마다 따로 구현돼 있던
   "여행지에서 너무 먼 장소" 검증을 `isFarFromDestination()` 공유 헬퍼로 통합, 기준 150km로 강화.
   여러 코드 경로 중 일부에만 안전장치가 있는 패턴은 항상 의심할 것.
5. **2-opt 가드 `n<4`가 과도하게 빡빡함** → 3-spot day에서 의미있는 swap이 막혀 anchor/서사
   가중치가 안 먹힘. `n<3`으로 완화.

---

## 🚀 실행

```bash
# 인프라
docker compose up -d

# 백엔드
./gradlew bootRun
# http://localhost:8080
```

---

_Last Updated: 2026-06-29_
