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
Migration: Flyway
Auth: JWT (jjwt 0.12.6) + OAuth 2.0
Security: Spring Security 6 (STATELESS)
HTTP Client: Spring RestClient (WebClient 아님)
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
│   │   │   │   ├── OAuthCallbackRequest.java
│   │   │   │   ├── OAuthCallbackResponse.java
│   │   │   │   ├── OAuthLoginResult.java
│   │   │   │   ├── OAuthUserInfo.java
│   │   │   │   ├── TokenRefreshResponse.java
│   │   │   │   └── TokenRefreshResult.java
│   │   │   ├── entity/
│   │   │   │   ├── OAuthProvider.java     # KAKAO, GOOGLE, NAVER
│   │   │   │   ├── RefreshToken.java      # Redis 기반
│   │   │   │   └── UserAuthProvider.java
│   │   │   ├── repository/
│   │   │   │   ├── RefreshTokenRepository.java
│   │   │   │   └── UserAuthProviderRepository.java
│   │   │   └── service/
│   │   │       ├── AuthService.java
│   │   │       └── oauth/                 # Strategy Pattern
│   │   │           ├── OAuthProviderStrategy.java
│   │   │           ├── OAuthStrategyFactory.java
│   │   │           ├── KakaoOAuthStrategy.java
│   │   │           ├── GoogleOAuthStrategy.java
│   │   │           └── NaverOAuthStrategy.java
│   │   │
│   │   └── user/                          # ✅ 구현 완료
│   │       ├── controller/UserController.java
│   │       ├── dto/
│   │       │   ├── ProfileResponse.java
│   │       │   └── ProfileUpdateRequest.java
│   │       ├── entity/
│   │       │   ├── User.java
│   │       │   ├── UserRole.java          # USER, ADMIN
│   │       │   └── UserStatus.java        # ACTIVE, DORMANT, SUSPENDED, WITHDRAWN
│   │       ├── repository/UserRepository.java
│   │       └── service/UserService.java
│   │
│   └── global/
│       ├── config/
│       │   ├── SecurityConfig.java        # CORS, JWT Filter, 공개/보호 경로
│       │   ├── JpaConfig.java             # Auditing
│       │   ├── RedisConfig.java
│       │   ├── RestClientConfig.java      # Spring RestClient
│       │   └── OAuthProperties.java       # @ConfigurationProperties
│       ├── entity/
│       │   └── BaseTimeEntity.java        # createdAt, updatedAt
│       ├── exception/
│       │   ├── ErrorCode.java             # AUTH_001~006, USER_001~002, COMMON_001/999
│       │   ├── BusinessException.java
│       │   └── GlobalExceptionHandler.java
│       ├── response/
│       │   ├── ApiResponse.java           # { success, data, error }
│       │   └── ErrorInfo.java
│       └── security/
│           ├── JwtTokenProvider.java       # HS256, access 30분, refresh 7일
│           ├── JwtAuthenticationFilter.java
│           ├── JwtProperties.java          # @ConfigurationProperties
│           └── UserPrincipal.java
│
├── src/main/resources/
│   ├── application.yml                    # 공통 설정
│   ├── application-local.yml              # 로컬 DB/Redis/JWT/OAuth 설정
│   └── db/migration/
│       ├── V1__init.sql                   # users, user_auth_providers
│       ├── V2__add_refresh_tokens.sql
│       ├── V3__add_refresh_token_revoked.sql
│       ├── V4__remove_email_unique_constraint.sql
│       └── V5__drop_refresh_tokens.sql    # Redis로 이관
│
├── docker-compose.yml                     # PostgreSQL 16 + Redis 7
├── .env                                   # JWT_SECRET, OAuth 키
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

_Last Updated: 2026-03-15_
