# 교통 경로 엔진 (`domain/routing`)

좌표 두 개와 출발 시각으로 실제 경로를 묻는 공통 계층이다. 실제 시간표로 확인한 경로, 노선은 있지만 운행일을 확정하지 못한 경로, 추정만 가능한 경로, 조회하지 못한 경로를 구분해 돌려준다. 생성기 연결은 아래 "생성기 통합 계약"을 따른다.

## 모델과 신뢰도

- `RouteQuery`: 출발지·도착지, 시간대 있는 출발 시각, 수단(`TRANSIT`·`WALK`·`BICYCLE`·`CAR`·`TAXI`)
- `RouteResult.status`: `FOUND`, `NO_CONNECTION`(데이터가 있는 공급자가 그 시각 연결을 못 찾음), `NOT_COVERED`(그 지역·날짜 데이터 없음), `UNAVAILABLE`(시간 초과·한도·장애). 하나라도 `UNAVAILABLE`이면 `NO_CONNECTION`으로 단정하지 않는다
- `RouteItinerary`: 출발·도착 시각, 소요, 환승, 구간(수단·노선·운영사·시각·거리), 요금(모르면 비움), `endpointGapMeters`(끝점을 정류장으로 옮긴 거리, 소요 시간 밖)
- `RouteSource`: 공급자, 근거(`RouteBasis`), 운영기관 데이터 여부, 받은 시각, 만료, `validity`(그래프 시간표 기간, 요청 날짜가 그 안인지, 실제로 쓴 시간표 날짜)
- `RouteBasis`(내부): `REALTIME` > `OFFICIAL_SCHEDULE` > `GTFS_SCHEDULE` > `ROAD_NETWORK` > `UNVERIFIED_SCHEDULE` > `ESTIMATED`
- `RouteConfidence`(생성기 계약): `LIVE_QUOTE`(아직 없음) · `OFFICIAL_SCHEDULE` · `GTFS_SCHEDULE` · `ESTIMATED`(도로망·미검증 시간표·빌린 시간표) · `UNAVAILABLE`(확인한 경로 없음, 이동 불가능 아님)

| 상황 | 결과 |
|---|---|
| 공식 시간표와 대조해 모든 대중교통 구간 일치(`ScheduleVerifier`) | `OFFICIAL_SCHEDULE` |
| 공식 시간표에 그 편이 없음(운휴·개정 뒤 사라진 편) | `UNVERIFIED_SCHEDULE` → `ESTIMATED` |
| 운행일이 있는 GTFS(코레일·JR·사철·공개 버스 피드) | `GTFS_SCHEDULE` |
| 달력을 믿을 수 없는 피드(`unverified-feeds`·`unverified-agencies`, 예: KTDB 평일 대표 시각표, 2012~2099 형식 달력). 구간마다 `RouteLeg.unverifiedSchedule`로 표시하고, 표시한 구간이 모두 공식 대조로 확인될 때만 올린다 | `UNVERIFIED_SCHEDULE` → `ESTIMATED` |
| 그래프 운행 기간 밖 날짜: 기간 안 같은 요일 시간표로 소요만 계산(`validity.requestedDateCovered=false`, 현지 시각 기준으로 옮김). 빌린 날에 연결이 없으면 `NOT_COVERED` | `ESTIMATED` |
| 역→역 열차에 출발지→역 접근 구간을 못 붙임(탈 수 있는지 모름) | `ESTIMATED` |
| 접근 구간 도착 뒤 탈 열차가 없음 | 다시 찾은 결과의 상태(`NO_CONNECTION`·`UNAVAILABLE`) |
| 도로·보행망(카카오·Valhalla·ORS) | `ROAD_NETWORK` → `ESTIMATED` |
| 경로 못 찾음 | `UNAVAILABLE` + 원인(status·reason) |

## 공급자와 연결 상태(2026-11-27 실측)

| 공급자 | 기능 | 연결 | 비고 |
|---|---|---|---|
| OTP 일본 8개 지역 + 전국 철도(역→역) | 철도·지하철·사철·버스·배 시간표 경로 | 실제 경로 조회 완료 | 전국 철도는 커뮤니티 피드(~2027-02-28). 지역 그래프도 이 기간까지만 싣고 그 뒤 날짜는 추정 |
| OTP 한국 | KTDB 전국(2025-03 평일 1일 대표 시각표, 자료신청) + 코레일 GTFS(실제 달력) + 코레일 GTFS-RT | 실제 경로 조회 완료 | KTDB는 미검증 등급. 코레일 피드가 SR 운행 SRT까지 담아 KTDB 열차는 공항철도 직통만 싣는다 |
| TAGO 열차·고속버스 | 공식 운행 시각 대조(`tago` 검증기) | 연결 완료 | SR 운행 열차도 대조된다(코레일 피드에 차량 이름 KTX-산천으로 실림). 시외버스 대조는 미연결 |
| 카카오모빌리티 | 한국 자동차·택시(교통 반영, 택시요금) | 연결 완료 | 무료 하루 10,000회 → 9,000회에서 멈춤 |
| Valhalla(자체) | 도보·자전거·자동차·택시, 차 경로 배 구간 | 한국+일본 타일 | 운영은 같은 설정에 전 세계 타일 |
| TfL | 런던 대중교통 | 연결 완료 | 키 없이 분당 50회 |
| ORS(HeiGIT) | 도보·자전거·자동차 | 연결, 기본 꺼짐 | 무료 플랜 상업 이용 불가(`commercial-use: false`) |
| OTP 싱가포르(제3국) | MRT·버스 | 실제 경로 조회 완료 | 커뮤니티 피드, 형식상 달력이라 미검증 |
| OTP 베를린 | VBB 대중교통 | 실제 경로 조회 완료 | 운영기관 GTFS |
| TMAP·Transitous | 정확도 비교용 | 비교만 | TMAP 무료 하루 10회, Transitous 비상업 |
| ODsay | 대중교통 | 인증 실패(IP·URI 등록 필요) | |
| ODPT 토큰 피드·DB OpenBoards·PRIM | | 미연결 | 승인 대기·Client Secret 없음·토큰 상태 불명 |

## 공급자 선택·비용 관리

- 공급자는 `application-routing.yml`의 `routing.providers.<id>` 항목 하나다. 영역은 ISO 3166-2 구역 코드(`coverage-areas`, 나라 코드면 전체, 경계는 `routing/admin-areas.json`)나 사각형으로 적는다. 앞 공급자가 데이터 밖이면 다음 공급자에 묻는다
- 역→역 공급자(`stop-to-stop`) 결과에는 엔진이 출발지→역, 역→목적지 구간을 지역 공급자로 붙이고, 열차는 접근 도착 + `transfer-minutes`(기본 10분) 뒤로 다시 고른다
- 장소 좌표가 길과 끊겨 출발지를 정류장으로 옮기면, 그 거리를 OTP 보행 속도(1.33m/s)로 걸은 뒤 떠나는 편을 묻는다. 걷는 시간은 호출부가 `endpointGapMeters`로 더한다
- 공급자 목록: `routing/providers.json` 한 곳에 데이터·경로 공급자 전체를 역할(CORE·FALLBACK·VALIDATION·DEPRECATED)과 연결 상태(코드 연결·키만 있음·없음), 인증정보 환경변수 이름과 함께 적는다. 설정 공급자와 목록이 어긋나면 테스트가 실패한다. `otp_region.py providers`는 키 설정 여부만(값 없이) 보여 준다
- 상태 노출: `/actuator/routing`(운영 프로필은 노출 안 함)에 공급자별 역할·키 설정 여부·분·일·월 사용량과 한도 대비 비율·마지막 성공·실패 시각·실패 종류(인증·호출 제한·시간 초과·오류)·차단 상태·대체 공급자 전환(앞 공급자가 답하지 못해 뒤 공급자가 낸 횟수, `routing.provider.fallback`). 인증 실패·호출 제한 응답은 한 번에 차단한다
- 한도 경고: 분·일·월 카운터를 Lua 스크립트 하나로 확인하고 모두 남았을 때만 함께 올린다(올림과 만료를 한 번에 해 만료 없는 카운터가 남지 않는다). 무료 한도 80%에 닿는 호출에서 `route budget warning`, 소진 때 `route budget exhausted`를 한 번 남기고 매시 사용량을 `route budget usage`로 남긴다. 카카오 월 한도는 `ROUTING_KAKAO_MONTHLY_LIMIT`(앱 콘솔 쿼터 값)
- 호출 관리: 분·일·월 한도(Valkey, 공급자 현지 날짜, 못 읽으면 호출 안 함) · 연속 실패 5회면 60초 차단 뒤 한 요청만 다시 묻기 · 같은 요청 동시 합치기 · 일정당 외부 호출 예산(`CallAllowance`) · 조회 한 번 40초 기한, OTP 한 호출 안의 재조회는 제한시간 3배 기한 · 공급자별 응답 시간·결과 측정(`routing.provider.call`) · 상업 이용 불가 공급자는 `allow-non-commercial`일 때만
- TAGO: 역·터미널 목록 6시간 캐시(다 못 받으면 10분 뒤 다시), 역 쌍·날짜별 시간표 6시간 캐시, 하루치가 한 쪽을 넘으면 `totalCount`까지 이어 받기(일부만 받으면 확인 못 함)
- 캐시: Valkey `route:v2`. 실제로 물어본 시각보다 이른 대중교통 요청에는 쓰지 않는다. `UNAVAILABLE`은 저장하지 않는다
- 택시 요금: 카카오가 주면 그 값, 아니면 `routing/taxi-fares.csv`(일본 운수국 공시 175개 지역, 한국 시·도·시·군 고시, 시행일 포함) 가장 가까운 기준점(40km 안)
- HTTP: 설정 base URL만 쓰고(원격 https, 로컬만 http), 리다이렉트를 따라가지 않으며, 오류 메시지·로그에 URL·키를 넣지 않는다

## 데이터 파이프라인(`scripts/routing`)

```bash
python3 -I scripts/routing/otp_region.py build jp-kanto     # 수요 기반: 수집 → 판 선택 → 자르기 → 임시 빌드 → 품질 검사 → 교체
python3 -I scripts/routing/otp_region.py check jp-kanto     # 같은 과정에서 교체만 하지 않는다(회귀 검증)
python3 -I scripts/routing/otp_region.py refresh            # 정기 갱신: 입력이 바뀐 지역만 차례로 다시 짓는다(실패 지역은 지금 그래프 유지, 종료 코드 1)
python3 -I scripts/routing/otp_region.py lifecycle --prefetch  # 판 만료 상태, 만료 예정 피드의 다음 판 사전 수집
python3 -I scripts/routing/otp_region.py catalog JP         # 카탈로그 형식·상태·공식 여부·MDB와 Atlas 원천 중복
python3 -I scripts/routing/otp_region.py baseline           # 입력 기록이 없는 지금 그래프의 기준선(내려받기 없이 검증된 지역만)
python3 -I scripts/routing/otp_region.py serve jp-kanto     # 127.0.0.1에만 바인딩
python3 -I scripts/routing/otp_region.py restart jp-kanto   # 교체·롤백 뒤 서버를 지금 graph/로 다시 띄우고 대표 구간 확인
python3 -I scripts/routing/otp_region.py rollback jp-kanto  # 교체 전 그래프로(정기 갱신 보류, release로 해제)
python3 -I -m unittest discover -s scripts/routing -p 'test_*.py'
```

1. 발견: 지역 정의(`regions.json`)의 핵심 피드 + Mobility Database(현·주 이름, 이름 목록이 없으면 카탈로그 `location.bounding_box`와 지역 영역이 겹치는 피드. `is_official`·`static_reference`(실시간 피드)·`redirect.id`(대체 관계)를 피드 목록에 남긴다) + ODPT 카탈로그(토큰 피드는 `ODPT_ACCESS_TOKEN`) + Transitland Atlas(나라별 후보 패턴). 신청해서 받는 자료는 `pathEnv`(예: `KTDB_GTFS_PATH`, 로컬 경로 또는 `s3://`)로 넣고, 판(`edition`)과 파일 해시(`sha256`)를 설정에 고정한다. 해시가 다르면 싣지 않고, 기준월에서 `editionMaxAgeDays`가 지나면 다음 판 신청을 알린다
2. 수집: 피드·카탈로그별 `refreshDays`·`catalogRefreshDays`로 다시 받기, 다시 받기 실패면 전에 받은 파일, 실패한 카탈로그 주소는 하루 동안 건너뜀, 내려받기 전체 기한
3. 정리(`gtfs_clip.py`): 영역·기간(오늘부터 365일) 자르기, 깨진 행·중복 키·없는 참조 제거, 표준이 아닌 노선 종류 변환, 겹치는 노선 이름 규칙 제외
4. 운행 기간: 달력이 형식상인 대표 시각표 피드(`representativeCalendar`, 예: KTDB)는 실제 달력이 있는 핵심 피드가 모두 운행하는 기간으로 달력까지 잘라 싣는다. 그 기간을 못 구하면 싣지 않는다. 그래서 OTP `serviceTimeRange`가 실제 시간표 기간을 넘지 않는다
5. 임시 빌드: 피드 id 명시(바꾼 글자가 있으면 원래 id 해시를 붙여 고유하게), GTFS-RT 갱신기(`realtime`). 빌드를 멈추게 한 피드는 자동 격리하고 `transitFeeds`·갱신기에서 빼고 재빌드(최대 5개). 설정 오류는 격리하지 않고 멈춘다. 제외 규칙이 한 노선도 안 걸린 피드는 빼고(판이 바뀌어 이름 형식이 달라진 경우), 지금 그래프에 있는 핵심 피드가 빠지면 빌드 전에 멈춘다
6. 피드 목록(`feeds-manifest.json`, 격리 뒤 실제로 든 피드): 원천·받은 시각·sha256·feed_info 기간·원래 운행일·실제로 실은 기간(`loadedFrom/To`)·운행 수·`calendarSuspect`(운행 기간 3년 초과)·판·판 만료
7. 이전 그래프와 비교: 핵심 피드 운행 수가 절반 밑으로 줄거나 핵심 피드가 격리되면 교체하지 않음
8. 품질 검사: 임시 포트로 새 그래프를 띄워 지역별 대표 구간(`smoke`, 역→역 그래프는 정류장 id)이 대중교통으로 나오는지, `expectRoute`가 있으면 그 노선이 나오는지. 실패하면 교체하지 않음
9. 교체·롤백: `staging → graph`, 기존은 `previous`. 교체가 중간에 끊겨 `graph`가 없으면 롤백이 `previous`를 되살린다

### 피드 수명 관리(`feed_versions.py`·`feed_compare.py`)

- 판 보관: 받은 피드는 `sources/versions/<피드>/<sha256 앞 16자>.zip`(읽기 전용)으로 판마다 따로 두고, `versions.json`에 원천 주소·카탈로그·라이선스·공식 여부·운영기관·받은 시각·실제 운행 기간(달력·운행 기준, feed_info 아님)·운행 수·품질 문제를 적는다. 다시 받기 전에 지금 사본을 먼저 판으로 남겨 새 다운로드가 원본을 덮지 않는다
- 판 선택: 오늘 운행하는 정상 판 중 가장 최근에 보인 판(원천이 예전 판으로 되돌리면 그 판). 같은 원천은 판 쿼리(`date`, GTFSデータリポジトリ `rid`)를 빼고 한 피드로 본다
- 품질: 필수 파일·달력·운행·정류장 좌표가 없거나 운행 수가 직전 정상 판의 절반 밑이면 그 판은 보관만 하고 싣지 않는다(이전 정상 판 사용, `warn` 줄)
- 만료: 운행 종료 14일 전부터 `expiring`, 매일 다시 받고 다음 판을 찾는다. 나머지는 원래 갱신 주기. 다음 판은 `regions.json` `nextEdition` 규칙(원천 호스트·판 쿼리)으로 지금 판 끝 다음 날을 묻고, 하루 한 번만 묻는다. 규칙이 없는 원천은 만료만 알린다
- 다음 판: 지금 판보다 늦게 시작하고 늦게 끝나며 나중에 받은 정상 판만 다음 판이다(개정으로 짧아진 새 판 뒤에 예전의 긴 판이 끼어들지 않는다). 끊는 날이 오늘 이전이면 잇지 않는다. 이어 싣지 못하는 다음 판이 있으면 상태는 `expiring`·`nextUnlinked`로 남고, 운행 수가 줄어 거부된 새 판은 `newestRejected`로 보고한다(정당한 감편은 피드 설정 `allowTripDrop`)
- 판 연결: 다음 판이 있으면 기간 연속성·정류장 id 일관성(같은 id의 위치 100m 이상·이름 변경)·노선 id 충돌·trip_id·service_id 충돌·겹친 날의 같은 운행·날짜별 운행 여부를 본다. 이어지면 판마다 OTP 피드 id를 따로 두고(`<피드>-v<시작일>`) 판별 기간으로 잘라 함께 싣는다. 다음 판에도 같은 실시간 갱신기와 핵심 피드 보호가 붙고, 판 사이에 빈 날이 있으면 지역 운행 기간은 앞 판까지다. stop_times가 400MB를 넘는 피드는 기간·정류장만 보고 운행 단위 검사는 건너뛰었다고 남긴다. 겹치면 새 판 시작 전날에서 앞 판을 끊는다. 정류장 id가 5% 넘게 다른 곳을 가리키면 잇지 않고 지금 판만 싣고 그 뒤 기간을 공백으로 알린다. id가 다른 내용으로 다시 쓰였으면(`mergeable: false`) 한 피드로 합치지 않는다. 결과는 그래프의 `feeds-lifecycle.json`
- 서빙: 그래프마다 `feeds-coverage.json`(피드별 정류장 범위·수단·실은 기간, 이어 실은 판은 한 피드로 묶음)을 남기고, OTP 공급자는 출발지·도착지 근처 피드가 그날 기간 밖이면 그 경로를 `UNVERIFIED_SCHEDULE`로 낮추고 사유를 단다. 다른 지역·피드 경로는 그대로다. 대표 시각표·형식상 달력은 이미 미검증이라 넣지 않는다
- 중복: SHA-256이 같으면 뺀다. 운행 지문(실제 운행일·정류장 순서(이름·좌표)·시각·승하차 제한·노선 종류·이름·배차 간격)이 다른 피드에 모두 들어 있으면 뺀다. 설정 피드는 빼지 않고, 남는 피드가 형식상 달력이거나 자르기 규칙(노선 제외·종류 변환)이 있거나 공식 피드를 비공식 피드로 바꾸게 되거나 뺄 피드에만 환승·요금·통로 파일이 있으면 빼지 않는다. 95% 넘게 비슷하면 후보로만 기록하고 둘 다 싣는다. 운영기관이 같다는 것만으로는 빼지 않는다. 남긴 피드가 빌드에서 격리되면 그 피드 때문에 뺀 피드를 다시 싣는다. 정류장이 크게 겹치는(90%) 쌍만 운행 지문을 만들고, 압축을 푼 stop_times가 400MB를 넘는 피드는 비교하지 않는다
- 다시 짓기: 그래프마다 입력 기록(`inputs.json`: 피드 판 해시·판별 기간·OSM 크기·시각·지역 설정·피드 설정·OTP 판·`GRAPH_INPUTS_VERSION`)을 남기고 `refresh`는 입력이 같고 그래프가 `graphMaxAgeDays`(30일)보다 새로우면 짓지 않는다. 같은 기계의 빌드·롤백은 잠금 하나로 막는다

새 나라에 필요한 것: `regions.json` 지역 항목(OSM 주소, 영역, 시간대, 포트, 품질 검사 구간), `admin_areas.py`로 경계에 나라 코드 추가, `application-routing.yml` 공급자 항목, (선택) Atlas 후보 패턴·택시 요금 행. 코드가 필요한 것은 그 나라 공식 API 어댑터(예: TAGO·TfL)와 공식 시간표 검증기뿐이다. 전 세계 단일 OTP 그래프는 만들지 않는다.

## 지역 그래프 실측(Apple M 시리즈 10코어 · 24GB, 2026-11-27, 순차 실행·스왑 아웃 0)

| 지역 | 적재 피드 | 빌드 | 자르기 최대 | OTP 빌드 최대 | 그래프 | 서버 기동 | 기동 직후 RSS·힙 | 조회 p50/p95 |
|---|---|---|---|---|---|---|---|---|
| 홋카이도 | 59 | 82초 | 1.9GB | 4.1GB | 225MB | 6.1초 | 2.11GB·0.9GB | 125/1506ms |
| 도호쿠 | 82 | 146초 | 1.9GB | 6.2GB | 335MB | 9.2초 | 2.54GB·1.2GB | 복합 묶음 |
| 간토 | 93 | 671초 | 3.5GB | 10.7GB | 885MB | 20.3초 | 6.07GB·4.5GB | 2232/4893ms |
| 주부 | 204 | 539초 | 4.8GB | 11.0GB | 800MB | 19.3초 | 5.75GB·3.8GB | 988/1403ms |
| 간사이 | 109 | 210초 | 3.1GB | 8.6GB | 495MB | 12.2초 | 4.34GB·3.7GB | 509/2520ms |
| 주고쿠 | 58 | 120초 | 2.1GB | 5.8GB | 318MB | 7.1초 | 2.35GB·1.8GB | 복합 묶음 |
| 시코쿠 | 94 | 67초 | 2.0GB | 3.2GB | 174MB | 5.1초 | 1.59GB·0.8GB | 717/717ms |
| 규슈·오키나와 | 132 | 208초 | 2.1GB | 6.4GB | 540MB | 11.2초 | 3.15GB·2.3GB | 312/840ms |
| 일본 전국 철도 | 1 | 24초 | 5.8GB | 9.2GB | 202MB | 8.1초 | 4.83GB·2.5GB | 복합 묶음 |
| 한국(KTDB 2025) | 2 | 358초 | 2.2GB | 11.1GB | 1,279MB | 24.3초 | 8.96GB·7.5GB | 893/5882ms |
| 싱가포르 | 2 | 192초 | - | 2.1GB | 120MB | 3.1초 | 1.32GB·0.4GB | 403/1115ms |

복합 묶음(전국 철도 + 출발·도착 지역 그래프를 함께 띄움) 조회 p50/p95: 도쿄↔교토 5458/10117ms, 도쿄→센다이 9379ms, 신오사카→히로시마·미야지마 1191/3822ms. 이 묶음은 서버 세 개를 함께 띄워 메모리 압축이 일어나므로 서버별 메모리는 단일 지역 묶음 값을 쓴다. 일본 9개 그래프의 고유 피드는 787개(그래프별 합 832, 경계에 걸쳐 두 그래프 이상에 실린 피드 38개)다. Valhalla 한국+일본: 타일 4.1GB, 서비스 메모리 약 240MB(타일은 메모리 매핑), 응답 10~30ms.

## 실제 경로 검증

`--spring.profiles.active=local,eval,routing --eval.mode=route --eval.route-cases=scripts/routing/coverage-cases.jsonl`(`RouteCompareRunner`, LLM 호출 없음)로 47개 구간을 지금 추정식과 나란히 적고, 구간마다 기대 수단·노선·운영사(정규식)와 맞는지 판정한다. 운영사별 표는 `coverage_report.py report coverage-targets.json`이 데이터 존재 → 피드 확보 → OTP 적재 → 경로 조회 성공 네 단계로 만든다. 2026-11-27 결과(일본·한국 그래프 순차 검증, 묶음마다 스왑 아웃 0): 47개 모두 경로 있음, 기대 일치 42개. 근거는 GTFS 시간표 32, 미검증 시간표 10, 공식 확정 3, 도로망 1(미야지마 배), 추정 1(2027-07, 기간 밖 시간표 빌림)이다.

| 불일치 구간 | 원인 | 해결에 필요한 것 |
|---|---|---|
| 신주쿠→혼고산초메(도에이 지하철) | 도에이 데이터는 실려 있고 JR·도쿄메트로 경로가 더 빨리 도착한다 | 데이터 문제 아님. 도에이만 지나는 구간으로 검증 구간을 바꾼다 |
| 교토역→기요미즈데라(교토 시영 버스) | 피드가 ODPT 토큰 대상이라 실리지 않았다 | ODPT 토큰 |
| 다카야마→시라카와고(노히 고속버스) | 노히 피드는 실렸지만 시라카와고 고속버스 노선이 없다 | 노히버스 고속버스 노선 GTFS(공개본 없음) |
| 수서→부산(SRT) | SR 열차는 코레일 피드에 있고 TAGO로 확정되지만 노선 이름이 차량 이름(KTX-산천), 운영사가 코레일로 적힌다 | 외부 데이터 불필요. TAGO 등급이나 열차 번호로 이름을 바로잡는다 |
| 목포→제주(여객선) | KTDB 2025에 하루 2편(01:00·08:30)뿐이라 오전 요청에 광주 경유 항공이 먼저 도착한다 | 날짜별 연안여객 공식 운항 시간표 |

서울→강릉은 지하철 구간이 KTDB(미검증)라, 서울→부산은 첫 KTX 편이 그날 TAGO 공식 시간표에 없어서 미검증으로 내려갔다. 두 구간 모두 경로 계산은 성공했고 기대 노선도 맞다.

## 생성기 통합 계약(생성기 코드는 이 브랜치에서 고치지 않는다)

- 후보 배치·점수 계산은 지금 추정(`DayBuilder.legOf`)을 쓰고, 하루 시각이 정해진 구간만 `RouteEngine.route(query, CallAllowance)`로 다시 잰다
- `RouteConfidence`가 `OFFICIAL_SCHEDULE`·`GTFS_SCHEDULE`일 때만 열차·버스 출발 시각을 확정 시각으로 쓴다. `ESTIMATED`는 소요 시간만 쓰고 "추정"으로 표시한다
- `UNAVAILABLE`은 이동 불가능이 아니다. 지금 추정으로 두고 HARD 위반(이동 불가)을 내지 않는다. 이동 불가 판정은 `NO_CONNECTION`이 모든 공급자에서 나오고 도로 경로도 없을 때만 한다
- `endpointGapMeters > 0`이면 그 거리의 도보·택시 시간을 호출부가 더한다(공항·역 장소 좌표가 부지 중심이라 생긴다)
- 바꿀 곳: `DayBuilder`(덮어쓰기 구간), `DraftItineraryPlanner`(허브 구간 2곳), `IntercityLegs`, `TransportLeg`(근거·공급자 필드), `ItineraryDataMapper` + 새 마이그레이션, 프론트 "추정" 표시, `DraftEvalRunner`의 eval.mode 목록에 `route`

## 운영 절차

### 입력 기준선(처음 refresh 전)

이 기능 전에 지은 그래프에는 `inputs.json`이 없어 `refresh`가 모든 지역을 다시 짓는다. `baseline`은 빌드 잠금을 잡고 내려받기를 끈 채(`OFFLINE`, 받은 사본만 쓰고 다음 판도 묻지 않음) 지금 파이프라인으로 수집·판 선택·중복 제외·자르기를 다시 돌린다. 그래프에 든 피드 집합, 피드별 자르기 요약(정류장·운행·stop_times·서비스·운행일 범위), 피드 목록의 원본 sha256, 지금 그래프 smoke 통과, OSM 사본이 그래프보다 오래됐는지를 모두 맞춰 본 뒤 같을 때만 `inputs.json`을 쓴다(그래프 파일은 건드리지 않음). 격리된 피드는 OTP가 다시 격리하므로 비교에서 뺀다. 하나라도 다르면 쓰지 않고 이유를 내므로 그 지역만 다음 refresh가 다시 짓는다.

2026-11-27 실행: 홋카이도·주부·간사이·주고쿠·시코쿠·규슈·전국 철도·한국·싱가포르 9개 지역 기록. 도호쿠(센다이 12월판 추가, 오가시 정시정로 피드 112편이 같은 날 낸 오가시 전체 피드에 모두 포함)와 간토(미즈호정 커뮤니티 버스 94편이 ODPT 미즈호정 전 노선 피드에 모두 포함)는 기록하지 않았다. 이어서 `refresh --check`는 9개 지역을 재사용하고 도호쿠·간토만 다시 지었다(교체 없음, smoke 통과, 나머지 피드는 해시·운행 수 같음).

교체 전 회귀 검사(지금 그래프와 staging을 같은 포트에 차례로 띄워 같은 질의): 도호쿠 9개·간토 7개 구간이 노선·시각까지 같다(미즈호정은 같은 시각·노선을 ODPT 피드가 낸다). 뺀 오가시·미즈호정 피드의 노선(6/6, 5/5)·정류장(392/392, 60/60)·날짜별 운행이 남은 피드에 모두 있다. 센다이는 11/30까지 11월판, 12/01부터 12월판만 운행하고(평일 3,390편, 겹치는 날 없음, 24시 넘는 운행 없음) 12/31부터 버스가 없다. 임시 ROOT에서 실제로 지어 교체하면 `inputs.json`이 그래프에 남고 다음 refresh가 재사용하며, 기준선 digest는 실제 빌드가 쓰는 digest와 같다.

### 그래프 전환

OTP 2.8은 기동할 때만 `graph.obj`를 읽고 실행 중 다시 읽는 API가 없다. 교체(`build`)와 롤백은 폴더만 바꾸고, 그 포트에 서버가 떠 있으면 `restart`를 안내한다.

1. `build <지역>`(검사 통과 뒤 교체) 또는 `rollback <지역>`
2. `restart <지역>`: 그 포트를 듣는 프로세스가 이 지역 `graph/`를 읽은 OTP인지 확인하고(아니면 멈춤) 끄고, `serve`로 다시 띄워 기동 로그를 기다린 뒤 대표 구간을 묻는다. 기동하는 동안(지역별 5~25초) 그 공급자는 `UNAVAILABLE`이고 엔진은 추정으로 둔다
3. `serve`는 띄울 때 그 그래프의 `feeds-coverage.json`을 `serving/`에 복사한다. 경로 엔진은 `serving/` 사본을 읽으므로 교체 뒤 재기동 전까지도 지금 서버 그래프 기준으로 판정한다
4. 롤백은 `hold.json`을 남겨 정기 갱신이 같은 입력으로 다시 지어 되돌리기 전 그래프로 바꾸지 않게 한다. 원인을 고친 뒤 `build`(교체되면 해제) 또는 `release`

AWS: 그래프를 `s3://…/<지역>/<입력 digest>/`처럼 판 경로로 올리고, OTP 서비스 태스크 정의의 그래프 경로만 바꿔 ECS 롤링 배포(새 태스크가 health 통과 뒤 옛 태스크 종료)로 바꾸면 중단이 없다. 롤백은 이전 태스크 정의 리비전으로 되돌린다.

### 빌드 잠금(분산 잠금은 설계만 있고 구현하지 않았다)

> 지금 구현된 잠금은 한 기계 안의 `flock` 하나뿐이다. 아래 분산 잠금은 운영(여러 Fargate 작업)을 위한 설계안이고 코드·인프라 어디에도 아직 없다. 여러 작업으로 돌리기 전에 구현해야 한다.

`build_lock()`은 `build/otp/.build.lock`에 대한 `fcntl.flock`이라 한 기계·한 파일 시스템 안의 프로세스끼리만 막는다(빌드·롤백·기준선·재기동·판 사전 수집이 함께 쓴다). 여러 Fargate 작업이 같은 지역을 동시에 지으면 막지 못한다. 분산 잠금 설계안(미구현):

- 잠금: 기존 Valkey에 지역별 키 `route:graph-lock:{지역}`을 `SET 값=<작업 id>:<토큰> NX PX 15분`으로 잡고, 빌드 중 5분마다 Lua로 "값이 내 것일 때만 PEXPIRE"로 늘리고, 끝나면 "값이 내 것일 때만 DEL"로 푼다. 작업이 죽으면 만료로 풀린다
- 펜싱: 잠금을 잡을 때 `INCR route:graph-fence:{지역}`으로 받은 번호를 교체 단계에 싣는다. 교체는 S3의 `<지역>/current.json`을 조건부 쓰기(`If-Match` 이전 ETag)로 바꾸고 번호가 지금 것보다 작으면 쓰지 않는다. 잠금 만료 뒤 늦게 끝난 옛 작업이 새 그래프를 덮지 못한다
- 실행: Step Functions 한 실행이 지역 목록을 Map(동시 실행 수 = 메모리 한도)으로 돌리고, 실행 이름을 `refresh-<날짜>`로 두어 같은 날 중복 시작을 막는다. 수요 기반 빌드도 같은 잠금을 거친다
- 비용: Valkey·S3·Step Functions 모두 기존 자원이라 새 자원이 없다

## 미완료(이번 단계에서 하지 않은 것)

| 항목 | 지금 상태 | 하려면 |
|---|---|---|
| 한도 경고 SNS 알림 | `route budget warning`·`exhausted` 로그와 `/actuator/routing`만 있다. 기존 SNS 토픽(`pipeline-alerts`)에 연결하지 않았다 | CloudWatch 로그 지표 필터 + 알람 → 기존 SNS(terraform, 승인 필요) |
| NeTEx·SIRI·GBFS | 구현 안 함. `catalog`가 카탈로그 형식별 개수만 센다(일본 MDB GBFS 4개) | 대상 국가와 데이터가 정해지면 형식 어댑터(NeTEx → GTFS 변환 등) |
| GTFS-RT 자동 연결 | MDB `static_reference`(일본 143개)를 피드 목록에 기록만 한다. 갱신기는 `regions.json`에 적은 것만 켠다 | 인증 없는 RT 피드를 피드 id와 묶어 갱신기 자동 생성 |
| 다음 판 자동 수집 | `nextEdition` 규칙은 ODPT(`date=`)만 있다. 나머지 원천은 만료 14일 전부터 매일 다시 받고 만료를 알리기만 한다 | 원천별 판 쿼리 규칙(설정)이나 카탈로그 갱신 감지 |
| 분산 빌드 잠금 | 설계 단계. 구현된 것은 한 기계 안 `flock`뿐이라 여러 Fargate 작업의 같은 지역 동시 빌드를 막지 못한다 | 위 "빌드 잠금" 설계(Valkey 잠금 + 펜싱 + S3 조건부 교체) 구현 |
| 무중단 그래프 전환 | 로컬은 `restart`(기동 동안 그 공급자 조회 불가) | ECS 롤링 배포로 그래프 경로 교체 |
| 카카오 월 한도 값 | 설정만 있고 값은 비어 있다(일 9,000회는 그대로 막음) | 앱 콘솔 쿼터 값을 `ROUTING_KAKAO_MONTHLY_LIMIT`에 넣기 |
| TMAP·LTA·DB·PRIM·ODsay | 인증정보만 있거나 없다(`providers.json` keyOnly·none) | 역할에 맞는 어댑터가 필요할 때 |
| `otp_region.py` 크기 | 1,800줄 한 파일 | 수집(카탈로그)·판·빌드·서빙 모듈로 나누기 |

## 남은 과제

- 데이터: KTDB는 1년 반 늦게 나오는 평일 대표 시각표라 날짜별 운행을 확정하지 못한다. ODPT 토큰 피드 53개(교토 시버스·요코하마 시영 버스 등), 오사카 시티버스·니시테쓰 버스(공개 데이터 없음), 일본 국내선 항공·미야지마 배 시간표 없음. Atlas 후보 140개는 원천 주소가 사라졌다(404 123·403 9 등)
- 형식상 달력: 간토 등 일부 버스 피드는 2099년까지로 적혀 있다. 지역 기간 맞춤으로 그래프 기간은 넘지 않지만 운행일 확정 여부는 따로 표시하지 않는다
- 중복 제외 피드의 다국어 이름(`translations`)은 `duplicates-meta/`에만 보관하고 그래프에 합치지 않는다
- 공식 대조: TAGO 시외버스 대조 미연결, ODsay IP 등록, DB OpenBoards Client Secret. 코레일 피드는 커뮤니티(mkuran) 재가공본이라 SR 열차 이름·운영사가 차량 기준으로 적힌다
- 운영 전: 별도 경로 서비스로 OTP·Valhalla 분리(2GB 앱 태스크에 넣지 않음), 내부망 OTP 주소 허용(지금은 원격 https·로컬 http만), 응답 크기 상한
- 계절·행사 피드도 끝나면 그 정류장 범위 경로를 미검증으로 낮춘다(정기 피드와 가르는 정보가 카탈로그에 없다)
- 베를린은 지역 id `de-berlin` 하나로 관리한다. 파이프라인 전 수작업 그래프(피드 목록·품질 검사 기록 없음)는 `de-berlin/archive/legacy-berlin`에 보관한다. `archiveKeep`을 켜면 보관본 정리가 이름순이라 이 폴더가 가장 새 보관본으로 남는다
- 기준선 이전 그래프에는 `feeds-coverage.json`이 없어 다시 지을 때까지 그 지역은 피드 단위 만료 판정을 하지 않는다(그래프 전체 운행 기간 판정만)
- 한도 카운터 키가 `route:budget:{공급자}:…`로 바뀌었다. routing 프로필을 운영에 켜는 첫 배포 전에는 영향이 없고, 켠 뒤 형식을 다시 바꾸면 그날 카운터가 0부터 다시 센다
- `.env.example`에 `TAGO_SERVICE_KEY`·`ODPT_ACCESS_TOKEN` 항목이 없다. `TOUR_API_SERVICE_KEY`가 URL 인코딩된 키면 두 번 인코딩돼 TAGO 대조가 조용히 꺼진다
- 카카오 키는 `KAKAO_MOBILITY_REST_KEY`가 없으면 로그인 앱 키(`KAKAO_CLIENT_ID`)를, TAGO 키는 `TAGO_SERVICE_KEY`가 없으면 `TOUR_API_SERVICE_KEY`를 쓴다. 같은 앱·계정이라 한도도 함께 쓴다
