# AGENTS.md

shg-trip 백엔드(Spring Boot 3.5, Java 21). 서비스 개요는 [README.md](README.md)에 있고, 로컬 실행은 아래 명령을 따른다. 설명과 커밋 메시지는 한국어로 쓰고, 코드·식별자는 영어로 쓴다.

## 명령

```bash
docker compose up -d                 # PostgreSQL(pgvector) · Valkey · LocalStack
./gradlew bootRun                    # 로컬 개발 서버, .env 자동 로드
./gradlew test                       # 단위 테스트 (integration 태그 제외)
./gradlew integrationTest            # 통합 테스트 (docker compose 필요)
./gradlew test --tests "*XxxTest"    # 특정 테스트
```

`.env`는 `.env.example`을 복사해 만든다. 직접 채울 키는 `DB_PASSWORD`(`docker-compose.yml`과 같은 값), `ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, OAuth 클라이언트 한 쌍 이상이고 나머지는 로컬 기본값이 있다.

배치는 `SPRING_PROFILES_ACTIVE=batch`, 품질 평가는 `--spring.profiles.active=local,eval --eval.mode=score|corpus|replay|files|pool|rejected`로 jar를 한 번 실행한다(`--eval.pipeline`은 기본 `recorded`로 생성 기록의 경로를 따르고 `v1`·`v2`로 고정할 수 있다. v2 재실행은 `SerialPlanner`로 하고, `--eval.selection=guided|picker`를 더하면 날짜는 `DayPicker`가 정한다. 둘 다 기록된 선택의 요청 의도·거점·숙소·예비 장소를 쓰고, guided는 기록된 선택이 날짜에 놓은 장소를 같은 거리 안에서 먼저 쓴다. `--eval.supplement=near`는 생성처럼 날마다 기준 좌표 둘레에서 후보를 보완한 뒤 다시 정해 DB를 읽으므로 로컬 복사본에 붙여 돌린다. `--eval.day-selector=haiku|sonnet`은 guided|picker에서 그날 후보를 모델이 먼저 고르게 하는 비교용이고 운영에는 붙이지 않는다). LLM을 부르는 실행은 끝난 뒤 프로세스가 종료됐는지 확인한다.

## 구조

- `domain/planning`: 생성 파이프라인. 조건 판정은 `InvariantChecker`(고치지 않음), 수리는 `RouteOptimizer`가 단계 순서를 정하고 단계 클래스(`DayRebalancer`·`LodgingPlanner`·`MealPlanner`·`DistanceBudget`·`FinalRepair` 등)가 맡는다. 튜닝 상수는 `ScheduleTuning` 한 곳에 둔다. 저장할 일정은 `QualityGate`가 `ItineraryRules`로 판정해 HARD면 막고 CORE면 '조정 필요'로 저장한다. v2 일정의 CORE 가운데 후보 풀에 바꿔 넣을 곳이 없던 것(`PoolLimits`)은 '조정 필요'로 세지 않고 알림만 남긴다. `SerialPlanner`는 고른 장소를 다른 날로 옮기지 않는 날짜별 확정이다. 운영은 `planning.pipeline=v2`(기본 v1)일 때 국내 여행만 이것으로 확정하고(`PlanningPipeline`), 날짜를 한 번 정해 날마다의 기준 좌표 둘레에서 후보를 보완한 뒤 다시 정한다. 예외가 나거나 결과가 구조 검증·필수 위반에 걸리면 그 생성만 v1으로 확정하고 생성 기록 `pipeline`에 남긴다. 재실행은 이 `pipeline`을 읽어 생성 때와 같은 경로로 돌린다. `DayPicker`는 날마다 기준 장소를 두고 거기서 닿는 범위 안에서 끼니와 관광지를 고른다. 끼니와 관광지는 선택 단계가 고른 장소를 같은 거리 안에서만 먼저 쓰고, 기준 장소는 요청 의도·선택 장소·유명도 순으로 정한다.
- `domain/tripchat`: 대화형 입력. 생성 요청은 `TripRequestMapper` 한 곳에서만 만든다.
- `domain/place/batch`: `batch` 프로필 전용 적재·보강·임베딩 작업.
- `src/main/resources/prompts/`: LLM 프롬프트, `db/migration/`: Flyway.

## 규칙

- 응답은 `ApiResponse.success(data)` / `ApiResponse.error(ErrorCode)`로 감싼다. 비즈니스 예외는 `BusinessException(ErrorCode.X)`이고, 새 에러는 `ErrorCode`에 추가한다.
- Service 클래스는 `@Transactional(readOnly = true)`, 변경 메서드에만 `@Transactional`을 붙인다. Entity를 그대로 반환하지 않고 DTO로 바꾼다.
- 스키마 변경은 새 `V{n}__설명.sql`로만 한다. 기존 마이그레이션은 고치지 않는다(`ddl-auto=validate`).
- `@Async` 메서드가 같은 클래스의 `@Transactional` 메서드를 부르면 프록시가 걸리지 않는다. `ItinerarySaveHelper`처럼 별도 빈으로 뺀다.
- `@Modifying` 쿼리에는 `@Transactional`을 직접 붙인다. `@Async` 호출부에는 트랜잭션이 없다.
- DB·Valkey가 필요한 테스트는 `@Tag("integration")`을 붙인다. Testcontainers는 쓰지 않고 docker compose 인프라에 붙는다. 속성 테스트는 jqwik을 쓴다.
- SQL은 파라미터 바인딩만 쓰고, 비밀값은 환경변수로 받는다.

## 일정 품질 작업

- 생성부터 돌리지 않는다. `EvalRunner` 재채점·리플레이와 `scripts/quality_metrics.sql`로 먼저 재고, 기준선은 [docs/eval-baseline.md](docs/eval-baseline.md)다. 여행자가 바로 문제로 느끼는 결함(같은 곳 쪼개기·어두운 야외·긴 공백·늦은 종료 등)은 `scripts/eval/traveler-checks.py`로 센다.
- 생성 요청 바디는 `ItineraryGenerateRequest` 필드명과 대조한다. 이름이 틀린 필드(`transportMode` 등)는 조용히 무시되고 기본값으로 떨어진다.
- 프롬프트 문구로 설득하기보다 후보 풀(`VectorSearchQueryService`)과 결정론 단계를 먼저 고친다.
- 여러 코드 경로 중 일부에만 있는 검증을 의심한다. 장소 판정은 `PlaceCategoryConstants`의 공용 게이트를 거치게 한다.

## 주석

- 코드만 봐서는 알 수 없는 이유만 현재형 한두 문장으로 남긴다. 이 코드가 없으면 무엇이 틀리는지를 쓴다.
- 코드를 말로 옮긴 것, 작업 경위·과거 사건·실측 서술, 대시(—)로 문장을 잇는 것은 쓰지 않는다. 튜닝 상수의 근거는 현재형 한 줄(예: `제주 관광지 체류시간 중앙값`)로 쓴다.

## 커밋

- 커밋과 푸시는 사람이 한다. 에이전트는 브랜치 작업과 커밋 메시지 초안까지만 한다.
- 제목은 `type: 무엇 + 무엇 수정|추가|전환|제거`(`feat` `fix` `refactor` `chore` `docs`), 본문은 `- `로 시작하는 명사형 bullet이다.
