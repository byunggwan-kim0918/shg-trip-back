# 지역별 이동 방법과 교통 엔진 연결 계약

대화에서 "삿포로·오타루는 대중교통, 후라노·비에이는 렌터카"처럼 지역마다 다른 이동 방법을 받으면, 생성 요청의 `layout.transports`(`TripLayout.AreaTransport`)에 지역별로 담긴다. 이 문서는 생성기가 교통 엔진(`domain/routing`, 별도 작업)에 넘길 입력과 받을 출력, 그리고 저장 후 편집에서 지역별 방법이 사라지는 현재 결함을 정리한다.

## 값과 판정 위치

| 값 | 뜻 | 교통 엔진 `TravelMode` |
|---|---|---|
| `walk` | 걷기 위주 | `WALK` |
| `car` | 렌터카·자가용 | `CAR` |
| `transit` | 대중교통만(택시 없음) | `TRANSIT` |
| `taxi` | 택시 위주 | `TAXI` |
| `any` | 섞어 다님. 가까우면 택시, 멀면 대중교통 | 구간 거리로 `TAXI`·`TRANSIT` 중 하나 |

- 구간 하나의 방법은 **구간을 떠나는 곳이 든 지역**의 값이다. 판정은 `TripConditions.modeAt` 한 곳이고, 짓기(`DayBuilder.Frame.modeFrom`)와 검사(`ItineraryRules.legLimits`, `TripConditionChecks.drivesThatDay`)가 같이 쓴다.
- 말한 지역 밖이면 여행 전체 값(`transportPref`, `walk|car|any`)을 쓴다.
- 지역 범위는 `TripConditions.Area.rangeKm`(도시 반경과 25km 중 큰 값, 도시 안 구역은 구역 반경)이다.

## 생성기 → 교통 엔진 (연결 시)

구간마다 `RouteQuery(origin, destination, departAt, mode)` 한 건이다.

- `origin`·`destination`: 앞 장소와 다음 장소 좌표
- `departAt`: 앞 장소를 떠나는 시각과 여행지 시간대(`TripCalendar`)
- `mode`: 위 표의 `TravelMode`. `any`는 구간 거리로 고른다(지금 추정과 같은 기준, `DayBuilder.TAXI_KM`)

받는 쪽 규칙은 다음과 같다.

- `RouteResult.status == FOUND`면 그 소요 시간·거리·요금을 구간 값으로 쓰고 `RouteConfidence`를 함께 저장한다.
- 그 밖의 상태(`NO_CONNECTION`·`NOT_COVERED`·`UNAVAILABLE`)는 지금의 추정(`DayBuilder.layoutLeg`, 도시 간은 `IntercityLegs`)으로 돌아간다. 추정한 구간은 그렇다고 표시해야 한다.
- 렌터카 인수·반납은 엔진이 아니라 생성기 검사(`RENTAL_HANDOFF`)가 본다.

## 저장 후 편집에서 지역별 방법이 사라지는 결함

재현:

1. `eval-records/layout/p128-layout`처럼 후라노·비에이를 `car`, 삿포로를 `transit`으로 만든 일정을 저장한다(3일차 후라노 구간은 `CAR`, 삿포로 시내는 `BUS`·`WALK`).
2. 3일차 장소 순서를 바꾼다(`PATCH /api/itineraries/{id}/steps/reorder`).
3. `ItineraryService.recalculateDayTransportation` → `SavedItineraryFacts.legBetween`이 모든 구간을 `GeoUtils.estimateLeg(..., "any", ...)`로 다시 잰다. 1km 미만은 `WALK`, 그 밖은 모두 시속 40km `CAR`가 된다. 삿포로 대중교통 구간도 `CAR`가 되고, 렌터카 구간의 거리 상한 판정도 생성 때와 달라진다.

필요한 계약:

- 판정 입력(`QualityGate.PlanContext.rules().conditions()`)에 `layout.modes`가 이미 저장된다(`QualityGateTest.planContextKeepsLayout`). 편집 재계산은 이 값을 읽어 구간마다 `TripConditions.modeAt(출발 장소 좌표, transportPref)`로 방법을 정해야 한다.
- 구간 값은 생성 때와 같은 함수(`DayBuilder.layoutLeg`, 교통 엔진 연결 뒤에는 위 `RouteQuery`)로 낸다. 편집 전용 추정(`GeoUtils.estimateLeg`)을 따로 두지 않는다.
- 판정 입력이 없는 옛 일정은 지금처럼 여행 전체 값으로 잰다.

이 결함은 교통 엔진 연결과 같은 구간 계산 경로를 바꾸므로, 엔진 연결 작업에서 함께 처리한다.
