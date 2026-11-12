<div align="center">

# SHG trip API

대화로 만드는 나만의 여행 일정

[서비스](https://myshgtrip.com) · [프론트엔드](https://github.com/byunggwan-kim0918/shg-trip-front)

<a href="https://myshgtrip.com">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/images/result-dark.png">
    <img src="docs/images/result-light.png" width="100%" alt="생성된 경주 2박 3일 일정의 1일차 타임라인과 지도">
  </picture>
</a>

</div>

SHG trip의 Spring Boot 백엔드입니다. 국내와 일본 여행지를 대상으로, 대화에서 받은 조건에 맞춰 장소를 고르고 방문 순서와 시간을 계산합니다.

## 주요 기능

- **대화로 여행 조건 입력** — 질문과 답변을 통해 여행 조건을 정리합니다.
- **날짜별 일정 생성** — 장소를 추천하고, 영업시간과 식사 시간, 이동거리, 숙소 위치를 반영해 일정을 구성합니다.
- **생성 진행 상황 확인** — 진행 상황과 완성된 날짜별 일정을 실시간으로 전달합니다.

## 일정 생성 구조

기본 생성 경로에서는 LLM이 여행 조건을 해석하고 방문할 장소를 고릅니다. 장소 검색은 DB에서, 동선과 시간 계산은 코드에서 처리합니다.

```mermaid
flowchart LR
    A["여행 조건 정리"] --> B["장소 후보 검색"]
    B --> C["날짜별 장소 선택"]
    C --> D["동선·시간 계산"]
    D --> E["검증·저장"]
```

장소 후보는 pgvector 유사도 검색에 인기도와 장소 유형을 함께 반영해 찾습니다. Sonnet은 검색된 후보 중에서 장소를 선택하고, `RouteOptimizer`가 방문 순서와 시간을 확정합니다.

## 설계와 개선

### LLM의 역할을 장소 선택으로 좁혔습니다

초기에는 Sonnet이 일정 전체를 작성하고, 별도의 LLM 호출로 채점과 수정을 반복했습니다. 운영 중 생성이 장시간 이어지다 실패하거나, 채점 결과가 정해진 JSON 형식을 지키지 못하는 문제가 있었습니다.

이후 장소 정보는 DB에서 가져오고, 영업시간과 이동거리 같은 제약은 코드로 처리하도록 바꿨습니다. LLM에는 후보 중에서 날짜별로 방문할 장소를 고르는 역할을 맡겼습니다.

### 생성된 일정도 별도로 평가합니다

일정이 완성된 뒤에는 `ItineraryScorer`로 끼니 누락, 이동거리 상한 초과, 출발 마감 초과 등을 확인합니다.

동선 로직을 수정할 때는 `EvalRunner`로 기존 장소 선택 기록을 다시 실행합니다. LLM을 재호출하지 않고 변경 전후의 결과를 비교할 수 있습니다.

평가 기준과 결과, 남아 있는 문제는 [일정 평가 문서](docs/eval-baseline.md)에 정리했습니다.

## 장소 데이터

Foursquare OS Places, 한국관광공사 TourAPI, Wikidata에서 매달 장소 데이터를 수집합니다. 수집한 정보는 보강과 임베딩을 거쳐 장소 검색에 사용합니다.

처리 과정과 실패 기준은 [장소 데이터 파이프라인](docs/place-batch-pipeline.md)에서 확인할 수 있습니다.

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| 서버 | Java 21, Spring Boot 3.5 |
| 장소 저장·검색 | PostgreSQL, pgvector |
| 인증 토큰·요청 제한 | Valkey |
| 입력 해석·장소 선택·가이드 문구 | Claude Haiku, Sonnet |
| 장소 임베딩 | OpenAI Embeddings |
| 데이터 처리·마이그레이션 | DuckDB, Flyway |
| 운영 인프라 | AWS ECS Fargate, RDS, ElastiCache, Step Functions |
| 인프라 관리 | Terraform |

## 관련 문서

- [일정 평가 기준과 결과](docs/eval-baseline.md)
- [장소 데이터 파이프라인](docs/place-batch-pipeline.md)
- [인프라 구성](terraform/README.md)

## 라이선스

All rights reserved. [LICENSE.md](LICENSE.md)
