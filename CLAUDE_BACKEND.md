# ☕ CLAUDE_BACKEND.md - Spring Boot 백엔드

> 백엔드 작업 시 적용되는 규칙. 공통 규칙은 루트 `CLAUDE.md` 참조.

---

## 📌 기술 스택 (실제)

```yaml
Language: Java 21
Framework: Spring Boot 3.5.9
Build: Gradle (Groovy DSL) - build.gradle
DB: PostgreSQL 16.11 (Docker)
Cache: Redis 7.4-alpine (Docker)
ORM: Spring Data JPA
Migration: Flyway (V1~V12)
Auth: JWT (jjwt 0.12.6) + OAuth 2.0
Security: Spring Security 6 (STATELESS)
HTTP Client: Spring RestClient (WebClient 아님)
AI: Anthropic Java SDK 2.15.0 (Claude Haiku 4.5 / Sonnet 4.6)
Lombok: 사용 중
Test: JUnit 5 + Mockito (Testcontainers 예정)
```

---

## 🏗️ 프로젝트 구조 (현재 상태)

```
shg-trip-back/
├── src/main/java/com/shg/trip/shgtrip/
│   ├── ShgTripBackApplication.java
│   ├── domain/
│   │   ├── auth/                          # ✅ 구현 완료
│   │   │   ├── controller/AuthController.java
│   │   │   ├── dto/
│   │   │   ├── entity/                    # OAuthProvider, RefreshToken, UserAuthProvider
│   │   │   ├── repository/
│   │   │   └── service/
│   │   │       ├── AuthService.java
│   │   │       └── oauth/                 # Strategy Pattern
│   │   │
│   │   ├── user/                          # ✅ 구현 완료
│   │   │   ├── controller/UserController.java
│   │   │   ├── dto/                       # ProfileResponse, ProfileUpdateRequest
│   │   │   ├── entity/                    # User, UserRole, UserStatus
│   │   │   ├── repository/UserRepository.java
│   │   │   └── service/UserService.java
│   │   │
│   │   ├── place/                         # ✅ 구현 완료
│   │   │   ├── controller/                # PlaceController, WishlistController
│   │   │   ├── client/                    # GooglePlacesClient, GooglePlaceDetail
│   │   │   ├── dto/
│   │   │   ├── entity/Place.java
│   │   │   ├── repository/PlaceRepository.java  # findByNameAndAddress, searchByKeyword, searchByRadius
│   │   │   └── service/                   # PlaceService, PlaceRefreshService, WishlistService
│   │   │
│   │   ├── itinerary/                     # ✅ 구현 완료
│   │   │   ├── controller/
│   │   │   │   ├── ItineraryController.java       # CRUD + finalize + share
│   │   │   │   └── SharedItineraryController.java # GET /api/shared/{token} (비인증)
│   │   │   ├── dto/
│   │   │   │   ├── ItineraryGenerateRequest.java  # PlanningMode.AUTO/MANUAL
│   │   │   │   ├── ItineraryResponse.java         # 상세 (steps 포함)
│   │   │   │   ├── ItinerarySummaryResponse.java  # 목록 (steps 미포함)
│   │   │   │   ├── ItineraryStepResponse.java
│   │   │   │   ├── AlternativeOptionResponse.java
│   │   │   │   ├── PlaceResponse.java
│   │   │   │   ├── ItineraryUpdateRequest.java
│   │   │   │   └── ShareLinkResponse.java
│   │   │   ├── entity/
│   │   │   │   ├── Itinerary.java         # @Version, soft delete, ItineraryStatus
│   │   │   │   ├── ItineraryStep.java     # place, alternatives, transportation
│   │   │   │   ├── AlternativeOption.java
│   │   │   │   └── StringArrayConverter.java  # TEXT[] ↔ List<String>
│   │   │   ├── repository/ItineraryRepository.java  # findByIdWithDetails (JOIN FETCH), findByShareToken
│   │   │   └── service/ItineraryService.java  # CRUD + 소유권 검증 + 공유
│   │   │
│   │   └── planning/                      # ✅ 구현 완료
│   │       ├── controller/PlanningController.java  # POST generate, GET stream (SSE)
│   │       ├── dto/
│   │       │   ├── EnrichedInput.java     # Haiku 보강 결과
│   │       │   ├── ItineraryData.java     # AI Tool Use 응답
│   │       │   ├── StepData.java
│   │       │   ├── PlaceData.java         # 11필드 (AI는 5필드만 채움, 방안 B)
│   │       │   ├── ValidationResult.java  # Hard/Soft 검증 결과
│   │       │   ├── ProgressEvent.java     # SSE 이벤트
│   │       │   └── GenerateJobResponse.java
│   │       └── service/
│   │           ├── ai/
│   │           │   ├── AIService.java     # 인터페이스
│   │           │   └── ClaudeAIService.java  # enrichInput, generateItinerary, enhance, regenerate
│   │           ├── validation/
│   │           │   └── ItineraryValidationService.java  # validateHard, validateSoft, validateWithRetry
│   │           ├── TravelPlannerService.java      # jobId + SSE emitter 관리
│   │           ├── ItineraryGenerationExecutor.java # @Async 파이프라인 (별도 빈)
│   │           ├── ItinerarySaveHelper.java        # @Transactional 저장 전용 (별도 빈)
│   │           └── ItineraryDataMapper.java        # AI 응답 → 엔티티 + Google Places 배치 조회
│   │
│   └── global/
│       ├── config/
│       │   ├── SecurityConfig.java        # /api/shared/** permitAll 추가
│       │   ├── AsyncConfig.java           # planningExecutor (DelegatingSecurityContextAsyncTaskExecutor)
│       │   ├── AnthropicClientConfig.java # AnthropicClient 빈
│       │   ├── AnthropicProperties.java   # anthropic.models.haiku/sonnet/opus
│       │   ├── JpaConfig.java
│       │   ├── RedisConfig.java
│       │   ├── RestClientConfig.java
│       │   └── OAuthProperties.java
│       ├── entity/BaseTimeEntity.java
│       ├── exception/
│       │   ├── ErrorCode.java             # AUTH, USER, ITINERARY, PLACE, WISHLIST, AI, EXTERNAL, VALIDATION, COMMON
│       │   ├── BusinessException.java
│       │   └── GlobalExceptionHandler.java
│       ├── response/
│       │   ├── ApiResponse.java
│       │   ├── PageResponse.java          # Page<T> 래퍼 (내부 구현 노출 방지)
│       │   └── ErrorInfo.java
│       ├── security/
│       │   ├── JwtTokenProvider.java
│       │   ├── JwtAuthenticationFilter.java
│       │   ├── JwtProperties.java
│       │   └── UserPrincipal.java
│       └── validation/                    # 커스텀 Validator
│
├── src/main/resources/
│   ├── application.yml
│   ├── application-local.yml              # anthropic.models, google.places 설정 포함
│   ├── prompts/
│   │   ├── enrich-input.txt               # Haiku 입력 보강 프롬프트
│   │   ├── generate-itinerary.txt         # Sonnet 일정 생성 프롬프트
│   │   ├── enhance-itinerary.txt          # Sonnet 일정 보강 프롬프트
│   │   └── regenerate-itinerary.txt       # Sonnet 일정 재생성 프롬프트
│   └── db/migration/                      # V1~V12
│
├── docker-compose.yml
├── .env                                   # JWT_SECRET, ANTHROPIC_API_KEY, GOOGLE_PLACES_API_KEY
└── build.gradle
```

---

## 🔐 인증 구조 (구현 완료)

```
OAuth Flow:
  프론트 → 소셜 로그인 → 인가 코드 → POST /api/auth/oauth/callback
  → AuthService.processOAuthCallback()
  → OAuthStrategyFactory → KakaoOAuthStrategy (등)
  → 신규 사용자 자동 가입 (User + UserAuthProvider)
  → JWT Access Token (30분) + Refresh Token (7일, httpOnly 쿠키)

Token Refresh:
  POST /api/auth/refresh (쿠키의 refresh_token 사용)
  → Refresh Token rotation (기존 삭제 + 신규 발급)

Security Filter Chain:
  JwtAuthenticationFilter → SecurityContextHolder
  공개: /api/auth/**, /actuator/health, /api/shared/**
  보호: 나머지 전부
```

---

## 🤖 AI 파이프라인 구조

```
일정 생성 요청 흐름:
  POST /api/itineraries/generate → jobId 반환
  GET  /api/itineraries/generate/{jobId}/stream → SSE 스트림

  TravelPlannerService (jobId + emitter 관리)
    → ItineraryGenerationExecutor (@Async, 별도 빈)
      1. enrichInput (Haiku 4.5) — 여행지 컨텍스트 보강
      2. resolveSelectedPlaces — Manual Mode 시 필수 검증
      3. generateItinerary (Sonnet 4.6) — Tool Use 스키마 기반
      4. validateWithRetry (ItineraryValidationService)
         - validateHard: 필수 필드, 날짜 범위, 대안 3~5개
         - validateSoft: 예산 초과, 일정 밀도, 교통 정보 (score 0~100)
         - 70점 미만 → enhanceItinerary (최대 3회)
         - 3회 실패 → regenerateItinerary
      5. ItinerarySaveHelper (@Transactional, 별도 빈)
         - ItineraryDataMapper.toEntity() + save()
         - Google Places API 배치 조회 + fallback 저장

  SSE 이벤트: progress (20→50→70→90→100%), complete, error

모델 설정 (application-local.yml):
  anthropic.models.haiku: claude-haiku-4-5-20251001
  anthropic.models.sonnet: claude-sonnet-4-6
  anthropic.models.opus: claude-opus-4-6 (미사용, 비용 효율성 고려)
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

---

## 📊 성능 고려사항

- N+1 쿼리 방지 (Fetch Join, EntityGraph)
- 페이지네이션 필수 (목록 API)
- Redis 캐시 활용 (장소 데이터 등)
- 트랜잭션 범위 최소화

---

## 🧪 테스트 규칙

- Service: JUnit 5 + Mockito 단위 테스트
- Repository: Testcontainers (PostgreSQL) 통합 테스트
- Controller: MockMvc
- Property: jqwik (예정)

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

_Last Updated: 2026-04-05_
