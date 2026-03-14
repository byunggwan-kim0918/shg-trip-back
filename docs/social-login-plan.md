# 소셜 로그인 구현 계획

## 1. 개요

소셜 로그인(카카오, 구글, 네이버) 기반 인증 시스템을 구축한다.
프론트엔드 UI는 구현 완료 상태이며, 이 문서는 **백엔드 구현 + 프론트엔드 연동**에 대한 상세 계획이다.

- **프레임워크**: Spring Boot 3.5 / Java 21
- **DB**: PostgreSQL 16 (Docker)
- **인증 방식**: JWT (Access Token + Refresh Token)
- **대상 Provider**: 카카오, 구글, 네이버 (Apple은 이후 추가)

---

## 2. 설계 결정

| 항목 | 결정 | 이유 |
|------|------|------|
| Access Token 전달 | 응답 body | 프론트엔드 메모리에서 관리, XSS 보호 |
| Refresh Token 전달 | httpOnly 쿠키 | JavaScript 접근 차단, CSRF 보호 |
| OAuth API 호출 | RestClient (Spring 6.1+) | spring-boot-starter-web에 포함, 추가 의존성 불필요 |
| Refresh Token 저장 | DB (refresh_tokens 테이블) | Redis 없이 토큰 무효화 가능, 인프라 단순화 |
| Refresh Token Rotation | 적용 | refresh 시 새 토큰 발급 + 기존 삭제. 탈취 감지 가능 |
| Provider 구현 패턴 | Strategy 패턴 | 동일 인터페이스로 3사(+향후 Apple) 확장 용이 |
| 세션 관리 | STATELESS | JWT 기반, 서버 세션 불사용 |

---

## 3. 인증 흐름

### 3.1 소셜 로그인 (신규/기존 사용자)

```
[프론트엔드]                          [백엔드]                         [소셜 Provider]
    │                                    │                                   │
    │  1. 소셜 버튼 클릭                   │                                   │
    │ ──────────────────────────────────> │                                   │
    │  window.location → Provider 인가 URL│                                   │
    │                                    │                                   │
    │  2. 사용자 로그인/동의               │                                   │
    │ ─────────────────────────────────────────────────────────────────────> │
    │                                    │                                   │
    │  3. /callback/[provider]?code=xxx  │                                   │
    │ <───────────────────────────────────────────────────────────────────── │
    │                                    │                                   │
    │  4. POST /api/auth/oauth/callback  │                                   │
    │     { provider, code }             │                                   │
    │ ──────────────────────────────────>│                                   │
    │                                    │  5. code → access_token 교환       │
    │                                    │ ────────────────────────────────> │
    │                                    │ <──────────────────────────────── │
    │                                    │                                   │
    │                                    │  6. access_token → 사용자 정보 조회 │
    │                                    │ ────────────────────────────────> │
    │                                    │ <──────────────────────────────── │
    │                                    │                                   │
    │                                    │  7. DB 조회/생성                    │
    │                                    │     - provider+providerId 확인     │
    │                                    │     - 이메일로 기존 유저 자동 연결    │
    │                                    │     - 또는 신규 유저 생성           │
    │                                    │                                   │
    │                                    │  8. JWT 발급                       │
    │                                    │     - Access Token 생성            │
    │                                    │     - Refresh Token 생성 + DB 저장  │
    │                                    │                                   │
    │  9. 응답 수신                        │                                   │
    │     body: { accessToken, isNewUser }│                                   │
    │     Set-Cookie: refresh_token       │                                   │
    │ <────────────────────────────────── │                                   │
    │                                    │                                   │
    │  10. 리다이렉트                      │                                   │
    │      isNewUser → /onboarding       │                                   │
    │      기존 유저 → /main              │                                   │
```

### 3.2 토큰 갱신

```
[프론트엔드]                          [백엔드]
    │                                    │
    │  Access Token 만료 감지             │
    │                                    │
    │  POST /api/auth/refresh            │
    │  Cookie: refresh_token=xxx         │
    │ ──────────────────────────────────>│
    │                                    │  DB에서 refresh_token 조회/검증
    │                                    │  새 Access Token 발급
    │  { accessToken }                   │
    │ <────────────────────────────────── │
```

### 3.3 로그아웃

```
[프론트엔드]                          [백엔드]
    │                                    │
    │  POST /api/auth/logout             │
    │  Cookie: refresh_token=xxx         │
    │ ──────────────────────────────────>│
    │                                    │  DB에서 refresh_token 삭제
    │  204 + Clear-Cookie                │
    │ <────────────────────────────────── │
    │                                    │
    │  메모리에서 accessToken 삭제        │
    │  /login으로 리다이렉트              │
```

---

## 4. API 명세

### 4.1 인증 API

#### POST /api/auth/oauth/callback
소셜 로그인 콜백 처리. 인가 코드를 받아 JWT 발급.

**Request**
```json
{
  "provider": "KAKAO",
  "code": "authorization_code_here"
}
```

**Response** (200 OK)
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi...",
    "isNewUser": true,
    "user": {
      "id": 1,
      "email": "user@example.com",
      "nickname": null,
      "profileImage": "https://..."
    }
  },
  "error": null
}
```
- `Set-Cookie: refresh_token=xxx; HttpOnly; SameSite=Lax; Path=/api/auth`

#### POST /api/auth/refresh
Access Token 갱신 + Refresh Token Rotation.

**Request**: Cookie에 refresh_token 포함

**Response** (200 OK)
```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOi..."
  },
  "error": null
}
```
- `Set-Cookie: refresh_token=새토큰; HttpOnly; SameSite=Lax; Path=/api/auth` (Rotation)
- 기존 refresh_token은 DB에서 삭제, 새 토큰으로 교체

#### POST /api/auth/logout
로그아웃. Refresh Token 무효화.

**Request**: Cookie에 refresh_token 포함

**Response**: 200 OK + `Set-Cookie: refresh_token=; Max-Age=0`

---

### 4.2 사용자 API

#### PATCH /api/users/profile
프로필 수정 (온보딩 시 닉네임 설정).

**Request** (Authorization: Bearer {accessToken})
```json
{
  "nickname": "여행자"
}
```

**Response** (200 OK)
```json
{
  "success": true,
  "data": {
    "id": 1,
    "email": "user@example.com",
    "nickname": "여행자",
    "profileImage": "https://..."
  },
  "error": null
}
```

#### GET /api/users/me
내 프로필 조회.

**Response** (200 OK): 위와 동일한 형태

---

## 5. DB 스키마

### 5.1 기존 테이블 (V1)

```sql
-- users
CREATE TABLE users (
    id              BIGSERIAL       PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL UNIQUE,
    nickname        VARCHAR(50)     NOT NULL,
    profile_image   VARCHAR(500),
    status          VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE',
    role            VARCHAR(20)     NOT NULL DEFAULT 'USER',
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

-- user_auth_providers
CREATE TABLE user_auth_providers (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    provider        VARCHAR(20)     NOT NULL,
    provider_id     VARCHAR(255)    NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_provider UNIQUE (user_id, provider),
    CONSTRAINT uq_provider_provider_id UNIQUE (provider, provider_id),
    CONSTRAINT chk_provider CHECK (provider IN ('KAKAO', 'GOOGLE', 'NAVER', 'APPLE'))
);
```

### 5.2 V2 마이그레이션 (추가)

```sql
-- refresh_tokens 테이블
CREATE TABLE refresh_tokens (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         BIGINT          NOT NULL,
    token           VARCHAR(512)    NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
-- token 컬럼은 UNIQUE 제약으로 자동 인덱스 생성되므로 별도 인덱스 불필요

-- nickname nullable 변경 (소셜 가입 시 닉네임 미설정 상태 허용)
ALTER TABLE users ALTER COLUMN nickname DROP NOT NULL;
```

---

## 6. 프로젝트 구조

```
shg-trip-back/src/main/java/com/shg/trip/shgtrip/
├── ShgTripBackApplication.java
├── global/
│   ├── config/
│   │   ├── SecurityConfig.java          # Security 설정 (JWT 필터, CORS, STATELESS)
│   │   ├── JpaConfig.java               # @EnableJpaAuditing
│   │   ├── OAuthProperties.java         # OAuth 3사 설정 바인딩
│   │   └── RestClientConfig.java        # RestClient Bean
│   ├── entity/
│   │   └── BaseTimeEntity.java          # createdAt, updatedAt 자동 관리
│   ├── exception/
│   │   ├── ErrorCode.java               # 에러 코드 enum
│   │   ├── BusinessException.java       # 비즈니스 예외
│   │   └── GlobalExceptionHandler.java  # 전역 예외 처리
│   ├── response/
│   │   ├── ApiResponse.java             # 공통 응답 래퍼
│   │   └── ErrorInfo.java               # 에러 정보
│   └── security/
│       ├── JwtProperties.java           # JWT 설정 바인딩
│       ├── JwtTokenProvider.java         # JWT 토큰 생성/검증/파싱
│       ├── JwtAuthenticationFilter.java  # JWT 인증 필터
│       └── UserPrincipal.java           # 인증 사용자 정보
├── domain/
│   ├── auth/
│   │   ├── controller/
│   │   │   └── AuthController.java      # /api/auth/** 엔드포인트
│   │   ├── dto/
│   │   │   ├── OAuthCallbackRequest.java
│   │   │   ├── OAuthCallbackResponse.java
│   │   │   ├── OAuthUserInfo.java       # 소셜 프로필 통일 DTO
│   │   │   └── TokenRefreshResponse.java
│   │   ├── entity/
│   │   │   ├── OAuthProvider.java       # enum: KAKAO, GOOGLE, NAVER, APPLE
│   │   │   ├── UserAuthProvider.java    # user_auth_providers 엔티티
│   │   │   └── RefreshToken.java        # refresh_tokens 엔티티
│   │   ├── repository/
│   │   │   ├── UserAuthProviderRepository.java
│   │   │   └── RefreshTokenRepository.java
│   │   └── service/
│   │       ├── AuthService.java         # 핵심 인증 비즈니스 로직
│   │       └── oauth/
│   │           ├── OAuthProviderStrategy.java    # 인터페이스
│   │           ├── KakaoOAuthStrategy.java       # 카카오 구현체
│   │           ├── GoogleOAuthStrategy.java      # 구글 구현체
│   │           ├── NaverOAuthStrategy.java       # 네이버 구현체
│   │           └── OAuthStrategyFactory.java     # Strategy 팩토리
│   └── user/
│       ├── controller/
│       │   └── UserController.java      # /api/users/** 엔드포인트
│       ├── dto/
│       │   ├── ProfileUpdateRequest.java
│       │   └── ProfileResponse.java
│       ├── entity/
│       │   ├── User.java                # users 엔티티
│       │   ├── UserStatus.java          # enum: ACTIVE, DORMANT, SUSPENDED, WITHDRAWN
│       │   └── UserRole.java            # enum: USER, ADMIN
│       ├── repository/
│       │   └── UserRepository.java
│       └── service/
│           └── UserService.java         # 프로필 조회/수정
```

---

## 7. 핵심 컴포넌트 상세

### 7.1 AuthService (핵심 인증 로직)

```
processOAuthCallback(provider, code):
  1. OAuthProvider.valueOf(provider) → enum 변환
  2. OAuthStrategyFactory → 해당 provider Strategy 획득
  3. strategy.getUserInfo(code) → OAuthUserInfo(providerId, email, nickname, profileImage)
  4. authProviderRepo.findByProviderAndProviderId() → 기존 연동 확인
     ├─ 있으면 → userRepo.findById() → 기존 유저
     └─ 없으면 → userRepo.findByEmail() → 동일 이메일 확인
        ├─ 있으면 → 기존 유저에 소셜 연동 추가 (자동 계정 연결)
        └─ 없으면 → 신규 User 생성 + 소셜 연동 저장
  5. JWT Access Token 생성 (claims: userId, role — email은 보안상 제외)
  6. Refresh Token 생성 (UUID) + DB 저장
  7. 반환: OAuthLoginResult(accessToken, refreshToken, isNewUser, user)
```

### 7.2 OAuthProviderStrategy (Strategy 패턴)

각 소셜 프로바이더의 공통 인터페이스:

```java
public interface OAuthProviderStrategy {
    OAuthProvider getProvider();
    OAuthUserInfo getUserInfo(String authorizationCode);
}
```

구현 흐름 (3사 공통):
1. authorization code → Provider token URI로 POST → access_token 수신
2. access_token → Provider user info URI로 GET → 사용자 정보 수신
3. Provider별 응답 구조를 `OAuthUserInfo`로 통일 변환

### 7.3 JwtTokenProvider

- **Access Token**: JWT 형태, claims에 userId/role 포함 (email은 탈취 시 노출 방지를 위해 제외), 1시간 만료
- **Refresh Token**: UUID 형태 (opaque), DB 저장, 7일 만료
- **Refresh Token Rotation**: refresh 요청 시 기존 토큰 삭제 + 새 토큰 발급. 이미 삭제된 토큰으로 접근 시 해당 유저의 전체 세션 무효화 (탈취 감지)
- Refresh Token을 JWT가 아닌 UUID로 만드는 이유: DB에서 조회/삭제로 즉시 무효화 가능

### 7.4 SecurityConfig

```
SecurityFilterChain:
  - CSRF disabled (JWT 사용)
  - CORS: localhost:3000, credentials: true
  - Session: STATELESS
  - /api/auth/** → permitAll
  - /actuator/health → permitAll
  - 나머지 → authenticated
  - JwtAuthenticationFilter → UsernamePasswordAuthenticationFilter 앞에 추가
```

---

## 8. 소셜 프로바이더별 API 정보

### 카카오

| 항목 | 값 |
|------|---|
| Token URI | `https://kauth.kakao.com/oauth/token` |
| User Info URI | `https://kapi.kakao.com/v2/user/me` |
| 응답에서 추출 | id → providerId, kakao_account.email → email, kakao_account.profile.nickname → nickname, kakao_account.profile.profile_image_url → profileImage |

### 구글

| 항목 | 값 |
|------|---|
| Token URI | `https://oauth2.googleapis.com/token` |
| User Info URI | `https://www.googleapis.com/oauth2/v2/userinfo` |
| 응답에서 추출 | id → providerId, email → email, name → nickname, picture → profileImage |

### 네이버

| 항목 | 값 |
|------|---|
| Token URI | `https://nid.naver.com/oauth2.0/token` |
| User Info URI | `https://openapi.naver.com/v1/nid/me` |
| 응답에서 추출 | response.id → providerId, response.email → email, response.nickname → nickname, response.profile_image → profileImage |

---

## 9. 설정 파일

### application-local.yml (추가 항목)

```yaml
jwt:
  secret: ${JWT_SECRET:dGhpcyBpcyBhIGxvY2FsIGRldmVsb3BtZW50IHNlY3JldCBrZXkgZm9yIGp3dA==}
  access-expiration: 3600000    # 1시간
  refresh-expiration: 604800000 # 7일

oauth:
  kakao:
    client-id: ${KAKAO_CLIENT_ID:}
    client-secret: ${KAKAO_CLIENT_SECRET:}
    token-uri: https://kauth.kakao.com/oauth/token
    user-info-uri: https://kapi.kakao.com/v2/user/me
    redirect-uri: http://localhost:3000/callback/kakao
  google:
    client-id: ${GOOGLE_CLIENT_ID:}
    client-secret: ${GOOGLE_CLIENT_SECRET:}
    token-uri: https://oauth2.googleapis.com/token
    user-info-uri: https://www.googleapis.com/oauth2/v2/userinfo
    redirect-uri: http://localhost:3000/callback/google
  naver:
    client-id: ${NAVER_CLIENT_ID:}
    client-secret: ${NAVER_CLIENT_SECRET:}
    token-uri: https://nid.naver.com/oauth2.0/token
    user-info-uri: https://openapi.naver.com/v1/nid/me
    redirect-uri: http://localhost:3000/callback/naver
```

### 의존성 추가 (build.gradle)

```groovy
// JWT
implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
```

---

## 10. 프론트엔드 변경사항

### callback/[provider]/page.tsx
- `credentials: 'include'` 추가 (쿠키 수신)
- ApiResponse 래핑으로 응답 접근 경로 변경: `data.data.accessToken`, `data.data.isNewUser`
- accessToken을 sessionStorage에 임시 저장

### onboarding/page.tsx
- `Authorization: Bearer {accessToken}` 헤더 추가
- `credentials: 'include'` 추가

---

## 11. 구현 순서

| Phase | 내용 | 파일 수 |
|-------|------|---------|
| 1 | 의존성 및 설정 | 4 |
| 2 | Global 공통 인프라 (ApiResponse, ErrorCode, Exception, BaseTimeEntity) | 7 |
| 3 | Entity & Repository | 9 |
| 4 | Security - JWT | 5 |
| 5 | OAuth Strategy (카카오/구글/네이버) | 8 |
| 6 | Service & Controller | 8 |
| 7 | Application 설정 & 프론트엔드 수정 | 3 |
| **합계** | | **44** |

---

## 12. 검증 방법

1. `./gradlew build` → 컴파일 성공
2. Docker PostgreSQL 기동 → 앱 실행 → V2 마이그레이션 적용 확인
3. 소셜 프로바이더 Developer Console에서 Client ID/Secret 등록 후:
   - `/login` → 소셜 버튼 클릭 → 인가 → 콜백 → JWT 발급 확인
   - 신규 유저 → `/onboarding` → 닉네임 설정 → `/main`
   - 동일 이메일 다른 프로바이더 → 자동 계정 연결 확인
4. `/api/auth/refresh` → 새 Access Token 발급 확인
5. `/api/auth/logout` → 쿠키 삭제 + DB에서 Refresh Token 삭제 확인

---

## 13. 에러 처리

### 에러 코드 정의

| 코드 | HTTP | 발생 상황 |
|------|------|----------|
| AUTH_001 | 400 | 지원하지 않는 provider |
| AUTH_002 | 401 | 소셜 인증 실패 (code 만료, 잘못된 code) |
| AUTH_003 | 401 | 유효하지 않은 Access Token |
| AUTH_004 | 401 | 만료된 토큰 |
| AUTH_005 | 401 | Refresh Token 없음 또는 만료 |
| AUTH_006 | 401 | 이메일 미제공 (소셜 프로바이더에서 이메일 동의 거부) |
| AUTH_007 | 401 | Refresh Token 재사용 감지 (탈취 의심 → 전체 세션 무효화) |
| USER_001 | 404 | 사용자 없음 |
| USER_002 | 409 | 닉네임 중복 |

### 카카오 이메일 필수 설정

카카오는 이메일이 "선택 동의"일 수 있으므로:
- 카카오 개발자 콘솔 → 동의항목 → `account_email`을 **필수 동의**로 설정
- 이메일 미제공 시 `AUTH_006` 에러 반환, 프론트에서 안내 메시지 표시

---

## 14. 보안 주의사항

| 항목 | 대응 |
|------|------|
| Access Token 탈취 | claims에 userId+role만 포함 (email 제외). 1시간 만료로 피해 범위 제한 |
| Refresh Token 탈취 | Rotation 적용으로 재사용 감지 → 전체 세션 무효화 |
| CSRF | SameSite=Lax 쿠키 + STATELESS 세션으로 방어 |
| XSS | Access Token은 메모리 전용 (localStorage 미사용), Refresh Token은 httpOnly 쿠키 |
| 쿠키 Secure 플래그 | 로컬: false, 프로덕션: true (application-prod.yml에서 별도 설정) |
| JWT Secret | 프로덕션에서 반드시 환경변수로 주입. 최소 256비트 Base64 인코딩 |
| 만료 토큰 정리 | 스케줄러로 `expires_at < now()` 레코드 주기적 삭제 (향후 구현) |

---

## 15. 향후 계획

- [ ] Apple 로그인 추가 (P8 키 기반 JWT client_secret 생성)
- [ ] 토큰 갱신 자동화 (프론트엔드 interceptor)
- [ ] 인증 미들웨어/가드 (Next.js middleware)
- [ ] 로그아웃 UI
