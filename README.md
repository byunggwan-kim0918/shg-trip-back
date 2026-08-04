# shg-trip-back

> Claude AI로 여행 일정을 자동 생성하는 shg-trip 서비스의 Spring Boot 백엔드.
> 목적지·기간·테마·예산을 입력하면 AI가 동선·시간·테마를 고려한 하루 단위 일정과 차선책을 만들어 SSE로 실시간 전송합니다.

전체 서비스 스펙과 상세 아키텍처는 프로젝트 루트의 [CLAUDE.md](../CLAUDE.md), 백엔드 세부 규칙은 [CLAUDE_BACKEND.md](CLAUDE_BACKEND.md)를 참조하세요.

## 기술 스택

| 항목 | 내용 |
|------|------|
| Language | Java 21 |
| Framework | Spring Boot 3.5.9 (Gradle, Groovy DSL) |
| DB | PostgreSQL 16 + pgvector (벡터 유사도 검색) |
| Cache | Redis 호환 Valkey 8 |
| Object Storage | S3 (로컬은 LocalStack 4 에뮬레이션) |
| ORM / Migration | Spring Data JPA + Flyway (`ddl-auto=validate`, 스키마는 Flyway 단독 관리) |
| Auth | JWT (jjwt 0.12.6) + OAuth 2.0 (Kakao / Google / Naver) |
| AI | Anthropic Java SDK 2.15.0 — Claude Haiku 4.5(`claude-haiku-4-5-20251001`) / Sonnet 4.6(`claude-sonnet-4-6`) |
| Embedding | OpenAI `text-embedding-3-small` (1536차원) |
| Infra | Docker Compose (Postgres + Valkey + LocalStack) |

## 주요 기능

- **소셜 로그인** — Kakao / Google / Naver 계정으로 가입·로그인하고 JWT로 세션을 유지합니다. Refresh Token 회전을 적용해 토큰 재사용(탈취)이 감지되면 전체 세션이 무효화됩니다.
- **AI 일정 자동 생성 (Auto Mode)** — 목적지·기간·테마·예산 등을 입력하면 Claude가 동선·시간을 최적화한 일정과 각 방문지의 차선책을 함께 생성합니다.
- **직접 선택 (Manual Mode)** — 원하는 장소들을 골라 그 장소 위주로 일정을 구성합니다.
- **자연어 문장 파싱** — "8월에 2박 3일 부산 맛집 위주로" 같은 문장을 구조화된 입력 필드로 변환합니다.
- **실시간 스트리밍** — 생성이 진행되는 동안 진행률과 완성되는 Day 카드가 SSE로 실시간 전송됩니다.
- **일정 편집** — 제목·태그 수정, 스텝(방문지) 드래그 재정렬, 스텝 삭제, 각 스텝의 대안 선택, 일정 확정(finalize)이 가능합니다.
- **공유** — 공유 링크를 만들어 비로그인 사용자도 볼 수 있는 공개 페이지로 일정을 공유합니다.
- **장소 검색** — 키워드·카테고리·반경으로 장소를 검색합니다(Google Places 연동).
- **남용 방지** — 30일간 생성 5회 쿼터, 입력 검증 반복 실패 시 일시적 생성 제한을 둡니다.

## 아키텍처 요약

### 일정 생성 파이프라인 (비동기 + SSE)

`POST /api/itineraries/generate`가 `jobId`를 즉시 반환하고 실제 생성은 `@Async`로 백그라운드에서 진행됩니다. 클라이언트는 `GET /api/itineraries/generate/{jobId}/stream`으로 진행률·Day 카드를 SSE로 수신합니다.

- **최적화 경로(정상)** — Haiku로 입력 보강 → DB 벡터 유사도 검색으로 장소 후보 확보 → Sonnet 1회로 일정 생성 → Java 검증 → 저장. LLM 호출을 최소화합니다.
- **Fallback 경로(후보 부족 시)** — Sonnet으로 일정을 생성한 뒤 Hard/Soft 2단계 검증 루프를 돌며 보강·재생성합니다.

동시 작업은 유저당 1개로 제한하고, 새 요청이 오면 진행 중이던 작업을 자동 취소합니다. 단계별 상세 흐름은 [CLAUDE.md](../CLAUDE.md)를 참조하세요.

### 인증 / BFF

프론트엔드(Next.js)가 BFF로서 백엔드를 호출하고, 백엔드가 발급한 JWT는 프론트에서 HttpOnly 쿠키로 암호화 보관됩니다. 백엔드는 Access Token(30분)과 Refresh Token(7일)을 발급하며, **Refresh Token은 DB가 아닌 Redis(Valkey)에만 저장**됩니다(TTL 7일).

### 장소 데이터

장소는 OpenAI 임베딩으로 벡터화되어 유사도 검색에 사용됩니다. Foursquare / TourAPI 시딩과 Google Places 동기화는 `batch` 프로파일의 배치 파이프라인이 담당합니다 — [docs/place-batch-pipeline.md](docs/place-batch-pipeline.md).

## 프로젝트 구조

```
src/main/java/com/shg/trip/shgtrip/
├── domain/
│   ├── auth/        # OAuth 콜백, JWT 발급/갱신
│   ├── user/        # 프로필 조회/수정
│   ├── place/       # 장소 검색, 임베딩, 배치 시딩(batch/)
│   ├── itinerary/   # 일정 CRUD, 편집, 공유
│   └── planning/    # AI 생성 파이프라인, SSE 스트림
└── global/          # config, exception(ErrorCode), response(ApiResponse), security(JWT)

src/main/resources/
├── application.yml            # 공통 + local/prod/batch/test 프로파일
├── prompts/                   # AI 프롬프트 템플릿 8종
└── db/migration/              # Flyway V1~V34
```

프롬프트 템플릿: `enrich-input`, `select-places`, `assemble-itinerary`, `generate-itinerary`, `enhance-itinerary`, `regenerate-itinerary`, `validate-soft`, `parse-sentence`.

## 로컬 실행

### 사전 요구사항

- Java 21
- Docker & Docker Compose

### 1. 인프라 실행

```bash
docker compose up -d   # Postgres(pgvector) + Valkey + LocalStack
```

### 2. 환경변수 설정

`.env.example`을 복사해 `.env`를 만들고 값을 채웁니다. `bootRun` 시 `.env`가 자동 로드됩니다.

```bash
cp .env.example .env
```

표기 규칙: `${VAR}` = 필수, `${VAR:기본값}` = 기본값 내장(선택). **비밀값은 `.env`에만 넣고 커밋하지 마세요.**

**① 로컬에서 직접 값을 채워야 동작하는 키**

| 키 | 설명 |
|------|------|
| `DB_PASSWORD` | Postgres 비밀번호. **docker-compose의 `POSTGRES_PASSWORD`와 동일하게** 설정 |
| `ANTHROPIC_API_KEY` | Claude API 키 (일정 생성) |
| `OPENAI_API_KEY` | OpenAI 임베딩 키 (장소 벡터 검색) |
| `GOOGLE_PLACES_API_KEY` | Google Places API 키 (장소 검색·동기화) |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | 카카오 OAuth (로그인하려면 최소 한 제공자 필요) |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | 구글 OAuth |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 OAuth |

**② 기본값이 내장되어 로컬에서는 비워도 되는 키**

| 키 | 설명 |
|------|------|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USERNAME` | DB 접속 정보 (기본 `localhost:5432/trip`, 사용자 `weShg`) |
| `REDIS_HOST` / `REDIS_PORT` | Valkey 접속 (기본 `localhost:6379`) |
| `JWT_SECRET` | JWT 서명 키. 로컬 dev 기본키가 내장되어 있으나 **프로덕션에서는 반드시 교체** |
| `AWS_REGION` / `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `S3_BUCKET` | S3 설정. 로컬은 LocalStack 기본값으로 동작 |

**③ 배치 전용 (`batch` 프로파일에서만 사용)**

| 키 | 설명 |
|------|------|
| `BATCH_ENRICH_ENABLED` / `BATCH_ENRICH_REGIONS` | 장소 보강 배치 on/off·대상 지역 |
| `TOUR_API_SERVICE_KEY` / `BATCH_TOURAPI_ENABLED` / `BATCH_TOURAPI_AREA_CODES` / `BATCH_TOURAPI_MAX_ROWS` | 한국관광공사 TourAPI 관광지 시딩 |
| `BATCH_GOOGLE_SYNC_ENABLED` / `BATCH_GOOGLE_SYNC_RECENT_DAYS` / `BATCH_GOOGLE_SYNC_DAILY_LIMIT` | Google Places 동기화 배치 |
| `FOURSQUARE_DATA_PATH` / `BATCH_FOURSQUARE_SOURCE` / `BATCH_FOURSQUARE_S3_BUCKET` | Foursquare CSV 시딩 소스 |

### 3. 서버 실행

```bash
./gradlew bootRun     # http://localhost:8080 (Flyway 마이그레이션 자동 실행)
```

프론트엔드는 `http://localhost:3000`에서 동작하며, 로컬 CORS 허용 오리진으로 설정되어 있습니다.

배치 작업은 `SPRING_PROFILES_ACTIVE=batch`로 실행합니다 — 자세한 내용은 [docs/place-batch-pipeline.md](docs/place-batch-pipeline.md).

### 테스트

```bash
./gradlew test                 # 단위 테스트 (DB/Redis 불필요, integration 태그 제외)
./gradlew integrationTest      # 통합 테스트 (docker compose up -d 필요)
./gradlew jacocoTestReport     # 커버리지 리포트 (build/reports/jacoco/test/html/index.html)
```

커버리지는 라인 기준 70% 이상을 유지합니다(DTO·Entity·Config·Global 제외).

## API 엔드포인트

### 인증 (공개)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/auth/oauth/callback` | OAuth 콜백 → JWT 발급 |
| POST | `/api/auth/refresh` | Access/Refresh 토큰 갱신 |
| POST | `/api/auth/logout` | 로그아웃 (Refresh 폐기) |

### 사용자 (인증 필요)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/users/me` | 내 프로필 조회 |
| PATCH | `/api/users/profile` | 닉네임 수정 |

### 장소 (인증 필요)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/places/{id}` | 장소 상세 |
| GET | `/api/places/search` | 장소 검색 |

### 일정 생성 (인증 필요, SSE)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/itineraries/parse` | 자연어 문장 → 구조화 입력 필드 |
| POST | `/api/itineraries/generate` | 생성 시작 → `jobId` 반환 |
| GET | `/api/itineraries/generate/{jobId}/stream` | SSE 스트림 (`progress`/`step-stream`/`complete`/`error`) |
| GET | `/api/itineraries/generate/{jobId}/result` | 생성 결과(`itineraryId`) 조회 |
| GET | `/api/itineraries/generation-quota` | 생성 쿼터·차단 잔여 조회 |

### 일정 관리 (인증 필요)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/itineraries` | 내 일정 목록 (페이지네이션) |
| GET | `/api/itineraries/{id}` | 일정 상세 |
| PUT | `/api/itineraries/{id}` | 일정 수정 (제목·태그) |
| POST | `/api/itineraries/{id}/finalize` | 일정 확정 |
| DELETE | `/api/itineraries/{id}` | 일정 삭제 (soft delete) |
| PATCH | `/api/itineraries/{id}/steps/{stepId}/select-alternative` | 스텝의 대안(차선책) 선택 |
| PATCH | `/api/itineraries/{id}/steps/reorder` | 같은 day 내 스텝 재정렬 |
| DELETE | `/api/itineraries/{id}/steps/{stepId}` | 스텝 삭제 |
| POST | `/api/itineraries/{id}/share` | 공유 링크 생성 |

### 공유 (비인증) / 헬스체크

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/shared/{token}` | 공유 일정 조회 (로그인 불필요) |
| GET | `/actuator/health` | 헬스체크 |

## 에러 코드

모든 응답은 아래 형식입니다.

```json
{
  "success": false,
  "data": null,
  "error": { "code": "AUTH_003", "message": "유효하지 않은 토큰입니다." }
}
```

| 코드 | 설명 | HTTP |
|------|------|------|
| AUTH_001 | 지원하지 않는 소셜 로그인 | 400 |
| AUTH_002 | 소셜 인증 실패 | 401 |
| AUTH_003 | 유효하지 않은 토큰 | 401 |
| AUTH_004 | 만료된 토큰 | 401 |
| AUTH_005 | 리프레시 토큰 없음 | 401 |
| AUTH_006 | 이메일 정보 미제공 | 401 |
| AUTH_007 | Refresh Token 재사용 감지 → 전체 세션 종료 | 401 |
| USER_001 | 사용자 없음 | 404 |
| USER_002 | 닉네임 중복 | 409 |
| ITINERARY_001 | 일정 없음 | 404 |
| ITINERARY_002 | 일정 접근 권한 없음 | 403 |
| ITINERARY_003 | 일정 버전 충돌 (Optimistic Lock) | 409 |
| PLACE_001 | 장소 없음 | 404 |
| PLACE_002 | 선택 장소가 여행지와 다른 지역 | 400 |
| AI_001 | AI 서비스 오류 | 503 |
| AI_002 | AI 서비스 타임아웃 | 504 |
| PLANNING_001 | 이미 생성 진행 중 | 409 |
| PLANNING_002 | 생성 취소됨 | 400 |
| PLANNING_003 | 입력 검증 반복 실패로 생성 일시 제한 | 429 |
| PLANNING_004 | 30일 생성 한도(5회) 초과 | 429 |
| EXTERNAL_001 | 외부 API 오류 | 502 |
| VALIDATION_001 | 입력 데이터 검증 실패 | 422 |
| COMMON_001 | 잘못된 입력 | 400 |
| COMMON_002 | 리소스 없음 | 404 |
| COMMON_999 | 서버 내부 오류 | 500 |

## DB 스키마

스키마는 Flyway가 단독 관리합니다(V1~V34, `ddl-auto=validate`).

| 테이블 | 설명 |
|--------|------|
| `users` | 사용자 (soft delete) |
| `user_auth_providers` | 소셜 로그인 제공자 연동 |
| `places` | 장소 (벡터 임베딩 포함, `name`+`address` UNIQUE) |
| `place_seeding_history` | 장소 시딩 이력 (배치) |
| `itineraries` | 일정 (`status` DRAFT/FINALIZED, `@Version` Optimistic Lock, soft delete) |
| `itinerary_steps` | 일자별 스텝 |
| `alternative_options` | 스텝별 대안(차선책) |

Refresh Token은 관계형 DB가 아닌 Redis(Valkey)에 저장됩니다(TTL 7일).

## 개발 가이드

- 응답은 항상 `ApiResponse.success(data)` / `ApiResponse.error(ErrorCode)` 사용
- 비즈니스 예외는 `throw new BusinessException(ErrorCode.XXX)` — 새 에러는 `ErrorCode` enum에 추가
- Service는 기본 `@Transactional(readOnly = true)`, 변경 메서드에만 `@Transactional`
- Entity 직접 반환 금지 — 반드시 DTO로 변환
- DB 변경은 `src/main/resources/db/migration/V{n}__{설명}.sql` 추가 (기존 마이그레이션 수정 금지)
