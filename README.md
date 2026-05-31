# shg-trip-back

> Claude AI로 여행 일정을 자동 생성해주는 서비스의 Spring Boot 백엔드.
> 목적지, 테마, 예산만 입력하면 AI가 최적 동선과 차선책까지 포함한 일정을 만들어줍니다.

## 기술 스택

| 항목 | 내용 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.5.9 |
| Build | Gradle (Groovy DSL) |
| DB | PostgreSQL 16.11 |
| Cache | Redis 7.4 |
| ORM | Spring Data JPA + Flyway |
| Auth | JWT (jjwt 0.12.6) + OAuth 2.0 (Kakao / Google / Naver) |
| AI | Anthropic Java SDK 2.15.0 (Claude Haiku 4.5 / Sonnet 4.6) |
| Infra | Docker Compose |

## 주요 기능

- 소셜 로그인 (Kakao / Google / Naver) + JWT 인증 (BFF 패턴)
- Refresh Token Rotation (Redis 저장, 탈취 감지 시 전체 세션 무효화)
- Claude AI 기반 여행 일정 자동 생성 (Auto / Manual Mode)
  - Haiku로 입력 보강 → Sonnet으로 일정 생성 (Tool Use)
  - HARD + SOFT 2단계 검증, 실패 시 최대 3회 보강 후 재생성
  - SSE로 진행률 실시간 전송
- 일정 CRUD + Optimistic Locking + 공유 링크
- 장소 검색 (키워드 / 카테고리 / 반경) + Google Places 연동
- 찜 목록 관리

## 구현 현황

| 도메인 | 상태 |
|--------|------|
| 인증 (OAuth + JWT) | ✅ 완료 |
| 사용자 프로필 | ✅ 완료 |
| 장소 검색 / 찜 목록 | ✅ 완료 |
| AI 일정 생성 파이프라인 | ✅ 완료 |
| 일정 CRUD + 공유 | ✅ 완료 |
| 초안 자동 저장 | 📋 예정 |
| 테스트 (JUnit / Testcontainers) | ⏸️ 보류 |

## 프로젝트 구조

```
src/main/java/com/shg/trip/shgtrip/
├── domain/
│   ├── auth/        # OAuth 콜백, JWT 발급, 토큰 갱신
│   ├── user/        # 프로필 조회/수정
│   ├── place/       # 장소 검색, 찜 목록, Google Places 연동
│   ├── itinerary/   # 일정 CRUD, 공유
│   └── planning/    # AI 파이프라인, SSE 스트림
└── global/
    ├── config/      # Security, JPA, Redis, Async, Anthropic
    ├── exception/   # ErrorCode, BusinessException, GlobalExceptionHandler
    ├── response/    # ApiResponse, PageResponse
    └── security/    # JwtTokenProvider, JwtAuthenticationFilter
```

## 실행

### 사전 요구사항

- Java 21
- Docker & Docker Compose

### 환경변수 설정

`.env.example`을 복사해 `.env`를 만들고 값을 채웁니다.

```bash
cp .env.example .env
```

```env
JWT_SECRET=                  # HMAC-SHA256 서명 키 (Base64)
ANTHROPIC_API_KEY=           # Anthropic API 키
GOOGLE_PLACES_API_KEY=       # Google Places API 키
```

### 서버 시작

```bash
# 인프라 (PostgreSQL + Redis)
docker compose up -d

# 백엔드 실행
./gradlew bootRun
```

서버: `http://localhost:8080`

> Flyway 마이그레이션이 자동 실행됩니다 (V1~V12).

## API 엔드포인트

```
# 인증 (공개)
POST /api/auth/oauth/callback
POST /api/auth/refresh
POST /api/auth/logout

# 사용자 (인증 필요)
GET   /api/users/me
PATCH /api/users/profile

# 장소 (인증 필요)
GET /api/places/search
GET /api/places/{id}

# 찜 목록 (인증 필요)
GET    /api/wishlist
POST   /api/wishlist/{placeId}
DELETE /api/wishlist/{placeId}

# 일정 생성 — SSE (인증 필요)
POST /api/itineraries/generate               → jobId 반환
GET  /api/itineraries/generate/{jobId}/stream → SSE 진행률 (20→50→70→90→100%)

# 일정 관리 (인증 필요)
GET    /api/itineraries
GET    /api/itineraries/{id}
PUT    /api/itineraries/{id}
DELETE /api/itineraries/{id}
POST   /api/itineraries/{id}/share

# 공유 (비인증)
GET /api/shared/{token}
```

## 에러 코드

| 코드 | 설명 | HTTP |
|------|------|------|
| AUTH_001 | 지원하지 않는 소셜 로그인 | 400 |
| AUTH_002 | 소셜 인증 실패 | 401 |
| AUTH_003 | 유효하지 않은 토큰 | 401 |
| AUTH_004 | 만료된 토큰 | 401 |
| AUTH_007 | Refresh Token 재사용 감지 → 전체 세션 무효화 | 401 |
| USER_001 | 사용자 없음 | 404 |
| USER_002 | 닉네임 중복 | 409 |
| ITINERARY_001 | 일정 없음 | 404 |
| ITINERARY_002 | 일정 접근 권한 없음 | 403 |
| ITINERARY_003 | 일정 버전 충돌 (Optimistic Lock) | 409 |
| PLACE_001 | 장소 없음 | 404 |
| AI_001 | AI 서비스 오류 | 503 |
| AI_002 | AI 서비스 타임아웃 | 504 |
| VALIDATION_001 | 입력 데이터 검증 실패 | 422 |
| COMMON_999 | 서버 내부 오류 | 500 |

모든 에러 응답 형식:
```json
{
  "success": false,
  "data": null,
  "error": { "code": "AUTH_003", "message": "유효하지 않은 토큰입니다." }
}
```

## DB 스키마

Flyway V1~V12 적용 완료.

| 테이블 | 설명 |
|--------|------|
| `users` | 사용자 (soft delete) |
| `user_auth_providers` | 소셜 로그인 연동 |
| `places` | 장소 정보 |
| `itineraries` | 일정 (Optimistic Lock `@Version`) |
| `itinerary_steps` | 일정 단계 |
| `alternative_options` | 차선책 |
| `user_place_wishlists` | 찜 목록 |

Refresh Token은 Redis에만 저장 (`@RedisHash "refresh_token"`, TTL 7일).

## 개발 가이드

### 브랜치 전략

```
main        # 배포 브랜치
develop     # 통합 브랜치
feature/*   # 기능 개발
fix/*       # 버그 수정
```

### 코드 컨벤션

- 응답은 항상 `ApiResponse.success(data)` / `ApiResponse.error(ErrorCode)` 사용
- 비즈니스 예외는 `throw new BusinessException(ErrorCode.XXX)`
- Service 클래스는 기본 `@Transactional(readOnly = true)`, 변경 메서드에만 `@Transactional`
- Entity 직접 반환 금지 — 반드시 DTO로 변환

### 새 도메인 추가 시

```
domain/{name}/
├── controller/
├── dto/
├── entity/
├── repository/
└── service/
```

DB 변경은 `src/main/resources/db/migration/V{n}__{description}.sql` 추가.
