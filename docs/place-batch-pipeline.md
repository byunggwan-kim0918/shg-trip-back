# 장소 데이터 적재 + 벡터 임베딩 배치 파이프라인

> `SPRING_PROFILES_ACTIVE=batch`로 구동되는 별도 컨테이너(ECS Fargate Scheduled Task)에서 실행.
> 메인 API 서버와 같은 코드베이스를 공유하지만 **런타임은 완전히 분리**되어 있고, `@Scheduled`를
> 쓰지 않는 `CommandLineRunner` 기반 1회성 실행 → 완료 시 컨테이너 종료.

## 전체 흐름

```
Step Functions (월 1회)
  ExtractFSQ — fsq-extractor/extract.py (Python + DuckDB, 별도 이미지)
        FSQ OS Places Iceberg → 한국 12개 도시 + 해외 여행지 → S3 foursquare/dt={yyyy-MM-dd}/foursquare-places.csv
        · 도시 판정: locality가 도시명이면 그 도시, 비었거나 구·군이면 region, 둘 다 비면 좌표 범위.
          어느 경우든 그 도시 좌표 범위 안이어야 함. region 컬럼은 표준 영어 도시명(Seoul, Jeju…)으로 출력.
          (locality가 자유 텍스트라 표기가 제각각 — 영어 정확일치였던 예전엔 18만 건 중 1.5만, 제주 46건)
        · 사용자 신고(unresolved_flags: closed·doesnt_exist·duplicate…) 있는 행 제외
        · 갱신 기준: 명소·야외 5년, 그 외 2년
        · 프랜차이즈 제외(음식·음료만, 브랜드 목록은 매달 데이터에서 다시 뽑음):
          한글은 "… OO점" 지점명이 20개 이상인 브랜드, 영문은 같은 이름이 20번 이상 반복되는 상호
          (Starbucks·Paris Baguette 등 57개, 12개 도시 5,121행). 영문 업종 일반명(cafe·coffee·bakery…)은 제외.
          한글은 반복 횟수로 판정하지 않는다 — 고향집·서울식당 같은 흔한 상호가 걸린다. 판정 목록은 실행 로그에 남는다
        · Iceberg 스캔엔 단순 조건만 걸고 한국 행을 임시 테이블로 받은 뒤 규칙 적용
          (리스트 컬럼 조건이 파일 프루닝으로 밀리면 iceberg 확장이 내부 오류로 죽는다)
        · 해외(JP·CN·VN·TW): src/main/resources/destinations.json 여행지(국가별 중심·반경)로 배정. region = 여행지 표준 영어명
          - 국가별 여행지 범위 합집합을 단순 조건으로 한 번에 스캔 → 반경 안 + 반경 대비 가장 가까운 여행지
          - 갱신 기준: 식당 1년, 명소·야외 5년, 그 외 2년
          - 역할(명소·식음료·숙소·시장·교통 허브)만 남기고, 명소·교통의 승강장·개찰구·출구·탑승구·주차장 이름 제외
          - 체인: 글로벌 브랜드(다국어 별칭 목록) + 대상 여행지 전체에서 같은 상호 100곳 이상인 현지 대형 체인 제외.
            여행 코스형 브랜드(이치란·딘타이펑·하이디라오·콩카페 등)는 허용, 같은 브랜드는 여행지당 3곳(신호 순)
            상호(stem)는 앞머리 괄호(【公式】)·첫 괄호 이하·" - 지점명"·끝의 "…店"을 떼서 비교
          - signal 컬럼: 전화·웹사이트·SNS·오래 영업(등록 후 2·5년)·구체 업종 점수(0~6). 한국 행은 빈 값
          - 여행지 규모(선별 후 행 수)별 명소·식음료 상한(DESTINATION_CAPS, 6만+ 5000/7500 … 4천 미만 없음).
            순위는 signal → fsq_place_id 해시. 숙소·시장·교통 허브는 상한 없음
          - EXTRACT_COUNTRIES(쉼표 구분, all = 전체)로 국가를 고른다. 없으면 한국만(월간 실행 기본값),
            목록에 없는 코드는 실패. 한국은 항상 포함
        · 규칙 테스트: fsq-extractor/test_extract.py (합성 테이블, CI extractor-test)
        · 이미지 빌드 컨텍스트는 shg-trip-back(-f fsq-extractor/Dockerfile) — destinations.json을 함께 넣는다
        · 필요 env: FSQ_ACCESS_TOKEN — Places Portal 개인 토큰, 유효기간 30일. 매달 갱신해 Secrets Manager에 넣어야 함
          선택 env: DUCKDB_MEMORY_LIMIT(기본 컨테이너 메모리의 75%, 알 수 없으면 3GB), DUCKDB_THREADS(기본 2)

  RunBatch — BatchJobRunner (CommandLineRunner, @Profile("batch"))
  [1/4] FoursquareSeeder.seed()
        S3FoursquareCsvReader(prod) / 로컬 FoursquareCsvReader(local)
          → foursquare/dt={yyyy-MM-dd}/ 최신 파티션 CSV 읽기
        → 비여행 카테고리 제외(은행·병원·학교·버스정류장·도로·주차장·헬스장·놀이터 등 —
          BLOCKED_CATEGORY_KEYWORDS. 교회·공항·역·마트는 명소/동선 기점이라 남김)
        → 이름 255자·주소 500자로 자름(name+address 인덱스 행 크기 한도)
        → 1000행씩 chunk 분할 → processChunk(): 청크 한 트랜잭션 upsert, 실패하면 행마다 다시 넣어 문제 행만 실패로 셈
          (연결·시간 초과, 행 연속 10건 실패는 청크 실패. 청크 실패 또는 실패 행 1% 초과면 단계 실패)
        → CSV 내부 중복 제거(fsq_place_id) → upsertFoursquarePlace() native upsert
          (이름이 바뀌면 임베딩·음식 종류·보강 시각·name_ko 초기화, deactivated_reason='stale' 행은 되살림, 'policy'는 그대로)
        → 빠진 장소 비활성화: 이번 CSV에 나온 (국가, 지역)마다 이번에 다시 나오지 않은 활성 Foursquare 행을
          deactivated_reason='stale'로 끔(1000행씩). Wikidata·TourAPI와 연결된 행은 다른 시더의 중복 판정에 쓰여 제외.
          실패 행이 난 지역은 건너뛰고, 실행 전부터 있던 활성 행의 30%(max-ratio)를 넘게 빠지면 그 지역은 건너뜀.
          BATCH_FOURSQUARE_DEACTIVATE_STALE=false(기본)면 지역별 대상 건수만 로그. 끈 행 수는 이력 deleted에 기록
        → PlaceSeedingHistory에 시작/완료/실패 이력 기록
  [1/4-보완] TourApiSeeder.seed()  (batch.tourapi.enabled=true일 때만)
        한국관광공사 TourAPI areaBasedList2 (12 관광지, 14 문화시설, 39 음식점, 32 숙박)
        → 대상: 광역시·제주는 areaCode, 도 안의 도시는 areaCode-sigunguCode (설정 토큰 형식)
        → 조회는 법정동 코드(lDongRegnCd·lDongSignguCd)로 변환해서 한다 — 지역코드 조회는
          areacode가 빈 신규 레코드를 놓친다(제주 관광지 162 vs 557건). 광주만 지역코드 유지
        → 쇼핑(38)은 신분류 SH01 백화점·SH02 몰·SH05 상가·SH06 시장만, 레포츠(28)는 골프 제외
          (수원 31-13, 강릉 32-1, 경주 35-2, 전주 37-12, 여수 38-13) — 기본값이 12개 도시 전부
        → 대상×타입당 최대 1000건, name+region 중복 스킵, source='tourapi', 대표이미지(firstimage) 저장
        → 필요 env: BATCH_TOURAPI_ENABLED=true, TOUR_API_SERVICE_KEY(data.go.kr, URL 인코딩된 키)
  [1/4-관문] GatewaySeeder.seed()  (batch.gateways.enabled, 기본 true)
        destinations.json gateways(국제선 공항)를 그 여행지 장소로 넣는다(source='gateway', place_role TRANSIT)
        → 관문 공항은 대개 여행지 반경 밖(나리타 57km, 간사이 38km, 푸둥 33km)이라 FSQ 추출에 들어오지 않는다
        → 범위는 BATCH_GATEWAYS_COUNTRIES(기본 ALL). 같은 여행지·이름의 관문 행이 있으면 건너뛴다
        → 생성 경로가 요청 여행지의 관문을 후보 끝에 붙인다(관문 없는 근교 여행지는 관문이 있는 상위 여행지까지 올라간다)
        → 목록에서 뺀 관문(역·국내선 공항)의 DB 행은 남아 있어도 후보에 붙이지 않는다
  [1/4-명소] WikidataLandmarkSeeder.seed()  (batch.wikidata.landmarks.enabled=true일 때만)
        지역 대표 명소(사찰·궁·박물관·해변·산 등)를 Wikidata에서 받아 없는 것만 적재
        → 한국: 지역 bbox, 언어판 min-sitelinks(5, 지방 도시는 min-sitelinks-by-region) 이상
        → 해외(destinations.json): 여행지 반경 조회, 도시 5·경관 여행지 min-sitelinks-scenic(2) 이상.
          도시·행정구역 항목(인구가 있고 섬·관광지 분류가 아님)·국가(P17)가 다른 항목(샤먼 반경의 진먼 섬) 제외,
          중국은 홍콩·마카오 안 항목을 따로 물어 제외.
          반경이 겹치면 추출기와 같은 규칙(거리/반경 최소, 좌표가 여러 개면 그 값이 가장 작은 좌표)으로 한 여행지에만 넣는다.
          이미 있는지는 인기도 매칭과 같은 이름 조건으로 본다(활성 행의 QID, 명소 적재 행의 QID 포함)
          name = 현지 라벨(일본 ja, 중국 간체, 대만 번체, 베트남 vi), name_ko = 한국어 라벨.
          이미 있는 QID의 명소 행은 이름·여행지를 이 규칙에 맞추고 비어 있는 name_ko를 채운다
        → 분류: 사찰·궁·박물관·공원·해변·산 같은 시설에 더해 구역형 명소(쇼핑·보행자 거리, 상업지구, 번화가, 구시가,
          역사 지구, 하나마치, 차이나타운, 광장, 보행자 교차로, 보드워크, 호수, 야시장). 큰길·거리·동네 분류는
          국도·철도 노선·주택가가 섞여 받지 않는다. 분류가 큰길뿐이거나 없는 대표 명소(왕푸징·부이비엔·지우펀 옛거리·
          나카미세)는 EXTRA_LANDMARKS로 지정해 언어판 기준 없이 받는다
  [2/4] WikidataSitelinksJob.run()  (batch.wikidata.enabled=true일 때만 — 운영 ecs.tf에서 켬)
        지역 bbox(장소 좌표 2~98 백분위 + 여유 0.05도)당 SPARQL 1회(언어판 1개 이상 항목만)
        + 항목 300개씩 한국어 별칭·분류(P31) 조회 + 실행당 식음료 분류 목록 1회. 일시 오류는 3회 재시도.
        → 항목이 장소를 고른다(유명한 항목부터, 반경 2km):
          · 장소 이름(괄호·도시명 제거, 띄어쓰기 무시) = 항목 라벨의 앞부분 단어 묶음(한글) 또는 라벨·별칭 전체.
            장소 이름에 항목에 없는 단어가 있으면 안 붙는다 — "창덕궁 후원"·"연세대학교 정문"이 본체를 못 가져간다
          · "A과 B" 꼴 장소(TourAPI "창덕궁과 후원")는 A로도 맞춘다
          · 식음료 분류 항목은 식당 행에만, 나머지는 식당이 아닌 행에만(한식당 "창덕궁"이 궁궐 점수를 못 가져간다)
          · 행정구역 행(States and Municipalities, 동·구·시 등으로 끝나는 Foursquare 행)·버스 정류장은 받지 않는다
          · 같은 이름의 소스별 중복 행에는 카테고리 등급(명소 > 숙소·상점·교통)이 가장 좋은 행 전부에 붙인다
        → 해외 지역(destinations.json)은 규칙이 다르다:
          · 범위는 여행지 반경(0.25도 격자로 나눠 조회), 장소 수 하한 없음
          · 항목의 ko·ja·zh(간체·번체)·en·vi 라벨·별칭 전체와 장소 이름 전체가 같아야 붙는다(앞부분 묶음 없음).
            장소 이름의 괄호 속 병기는 뗀다
          · 문자 종류가 바뀌는 이름("外滩 The Bund")은 뒤 묶음이 같은 항목 이름 그대로이거나 이름 + 분류어(temple·shrine·
            market·park 등)일 때만 첫 묶음으로 붙는다("龍安寺 Ryoan-ji Temple"은 붙고 "東大寺 Todaiji Nandaimon"은 안 붙음)
          · 끝 지점명 단어(…店·…門市)를 뗀 이름은 식음료 항목(체인)에만 붙는다("一蘭 天神西通り店" → 一蘭, "黒門市場 鮮魚店"은 안 붙음)
          · 도시·행정구역 항목(인구가 있고 섬·관광지 분류가 아님)은 붙이지 않는다. 에노시마(섬)·시탕(AAAAA 관광지 고진)은 붙는다
          · 이름 전체가 같은 항목만 한국어 라벨로 비어 있는 name_ko를 채운다(채운 이름은 매칭이 바뀌어도 남는다)
        지역 전체를 0으로 덮고 매칭분만 쓰는 걸 한 트랜잭션으로 매번 전량 재계산. 조회가 하나라도 실패하면 그 지역은 지난 값 유지.
        BATCH_WIKIDATA_REGIONS·BATCH_WIKIDATA_COUNTRIES로 범위를 좁힌다(명소 적재도 같은 설정).
        국가 기본값은 KR — 나라를 적재할 때마다 늘린다(EXTRACT_COUNTRIES와 같이). 대소문자 무관, ALL은 전 국가,
        빈 값·모르는 코드는 단계 실패. 일본을 넣으면 기존 일본 명소 행 이름이 현지어로 바뀌므로 name_ko를 화면·프롬프트에
        쓰는 배포 뒤에 넣는다. WDQS 429는 Retry-After만큼 기다려 재시도.
        ※ 보강보다 먼저 돈다 — 보강이 인기도 순으로 처리하려면 필요
  [2/4-사진] CommonsImageJob.run()  (batch.commons-images.enabled, 기본 true)
        사진이 없고(image_url IS NULL) Wikidata 항목이 있는 활성 방문지(NOT_VISITABLE 제외), 유명한 곳부터
        → 항목 200개씩 SPARQL로 대표 사진(P18, 여럿이면 이름순 첫 파일)
        → 파일 50개씩 위키미디어 공용 imageinfo(960px 썸네일·Artist·LicenseShortName)
        → image_url(upload.wikimedia.org 썸네일 주소)·image_credit(태그 뗀 작성자)·image_license·image_source_url(파일 페이지).
        파일은 받지 않고 주소만 둔다(TourAPI 사진과 같은 방식). 라이선스가 없는 파일은 쓰지 않고, 그사이 사진이 채워진 행은
        덮지 않는다. 실패 요청이 20%를 넘으면 단계 실패. 역할 기록 뒤라 비방문 장소에는 붙지 않는다.
        일정 표지는 사진 있는 스텝이 없으면 일정 지역에서 사진이 있는 가장 유명한 관광지를 쓴다(ItinerarySaveHelper).
  [3/4] BatchEnrichScheduler.enrich()  (batch.enrich.enabled=true일 때만)
        ※ 환경 규약: 운영(AWS)은 상시 ON(terraform/ecs.tf batch task에
          BATCH_ENRICH_ENABLED=true 고정) → 임베딩 전 항상 보강. 로컬은 기본값 false →
          enrich 없이 적재(비용·시간 절약). enriched_at IS NULL 게이트라 신규 유입분만 증분 보강.
        enriched_at IS NULL AND active=true 인 장소를 1000건씩 조회 — 인기도 → 비식당 → id 순,
        Wikidata 매칭을 아직 거치지 않은 행(wikidata_matched_at IS NULL)은 다음 실행으로 미룸
        (인기도 없이 보강하면 유명 식당이 설명 없이 완료된다. 매칭 실패 지역·국가 범위 밖 신규 행이 여기 걸린다.
        미룬 건수는 "Wikidata 매칭 전이라 보강을 미룬 장소 N건" 로그),
        응답이 3번 실패한 행(enrich_attempts ≥ 3)도 제외("보강 응답이 3번 실패해 제외된 장소 N건" 로그). 장소 탓인
        실패만 센다: 응답이 JSON이 아닌 경우와 성공이 있는 묶음의 요청 오류(invalid_request_error). 만료·취소,
        일시 오류(overloaded·api_error), 성공 0건 묶음의 오류(모델명 등 묶음 전체 장애), 결과 누락, 저장·코드 오류는
        세지 않는다. 원문 이름·카테고리가 바뀌면 0으로 돌아간다.
        이번 실행에서 실패한 id는 쿼리에서 제외. 연속 3개 청크가 전부 실패하면 중단(API 키 오류 등).
        결과 대기 시간 초과(batch.enrich.max-poll-duration, 기본 24h)·전부 만료된 묶음은 중단 횟수에 세지 않는다.
        결과 다운로드 실패는 센다(batchId를 로그에 남겨 resume으로 다시 반영). 이번 실행 실패가 2만 건을 넘으면 멈춘다.
        상태 조회가 429 아닌 4xx(없는 batchId·키 오류)면 바로 실패. 제출·상태 조회의 429는 Retry-After만큼 기다려 재시도.
        batch.enrich.max-duration(기본 10h)이 보강 단계 전체(결과 대기 포함) 상한이다. 지나면 새 묶음을 내지 않고
        기다리던 묶음도 끊는다(Step Functions 36h 안에 임베딩 단계까지 돌게). 나라 적재 단독 실행은 늘린다.
        (BATCH_ENRICH_REGIONS="Jeju,..."로 지역 스코프 가능 — 비우면 전체. 운영은 비워서 전 지역)
        (BATCH_ENRICH_CATEGORY_PREFIX="Dining and Drinking"으로 카테고리 스코프 가능 — 대상 순서가 명소 먼저라 식당 견본을
         따로 뽑을 때 쓴다. BATCH_ENRICH_MAX_PER_REGION은 지역 목록과 함께 써야 지역별로 센다)
        → Anthropic Batch API(Haiku 5.5, thinking off, 50% 할인) 제출 → 폴링 → 결과 JSON 파싱
        → tags/description/recommended_time_slots 갱신
        → 식당·카페·술집(카테고리 "Dining and Drinking …")은 DiningEnrichment 규칙:
          업종(식당/카페/베이커리/디저트/술집/간식)을 카테고리로 정해 태그 첫 자리에 넣고, 업종과 어긋나는
          태그(식당의 카페·커피·빵, 카페의 한식·식당)·홍보어·지역명은 코드로 지운다 — 동선 층의
          isMealPlace/isCafeLike가 같은 키워드 목록(isNonMealTag/isRestaurantTag)으로 판정한다.
          총칭("Dining and Drinking > Restaurant")일 때만 모델이 이름을 보고 업종을 고른다.
          설명은 Wikidata에 잡힌 곳(sitelinks > 0)만 받는다(모델이 동네 식당을 모른다). 체류시간은 업종 기본값,
          입장료는 넣지 않는다. 출력은 평균 40토큰대로 관광지 프롬프트(240토큰)의 1/5 수준(입력은 규칙이 길어 더 많다)
        → 해외 장소(country JP·CN·TW·VN, OverseasEnrichment)는 같은 항목에 더해:
          · 프롬프트에 국가·여행지(한국어 이름)·주소
          · name_ko가 비어 있으면 한국어 표시 이름을 묻는다(한국 통용명 우선, 없으면 외래어 표기법 발음 + 일반 명사는
            한국어, 지명은 뜻으로 옮기지 않음, 지점명은 "○○점"). 이미 있는 name_ko(Wikidata·보정표)는 덮지 않는다.
            저장한 이름은 다시 묻지 않아 검증한다: 괄호 병기는 떼고, 한자·가나가 남거나 "없음"·자리표시자면 버린다
          · 관광지 입장료는 현지 통화 금액(admission_fee_local) + 통화(admission_currency), admission_fee(원)는 비움.
            통화별로 있을 수 없는 금액(천 단위로 줄인 동, 원화로 적은 엔)은 버린다
          · 식당 태그는 한국에서 부르는 요리 이름(라멘·훠궈·쌀국수…), 음식 종류는 요리 이름 → MEAT·SEAFOOD·NOODLE·SOUP
          · 그 장소의 나라·여행지 이름도 지역명 태그로 지운다(다른 나라 이름은 음식 태그라 남긴다)
          · traveler_fame(0 그 밖 / 1 현지에서 이름난 곳 / 2 가이드북·미쉐린급, 확신 없으면 0)을 묻는다.
            Wikidata 언어판이 거의 없는 식당·야시장의 인기도 대용이다. 식당은 1 이상일 때만 설명을 받는다
          · 야시장(FSQ "Dining and Drinking > Night Market")은 관광지 프롬프트로 보강해 MARKET 역할을 받는다.
            인기도 매칭도 비식음료 항목과 맞춘다
          한국 장소 프롬프트는 그대로다
        → 결과는 보강 컬럼만 쓴다(writeEnrichment). 엔티티째 저장하면 제출~결과 사이(최대 하루)에 바뀐 컬럼을
          그때 읽은 값으로 되돌린다
        → 성공분은 바로 재임베딩, 실패분만 embedding=NULL → 다음 단계에서 재임베딩
          (임베딩 텍스트가 tags/description을 포함하므로 재임베딩 없이는 검색에 미반영)
        → 결과 반영만 다시 할 때: BATCH_ENRICH_RESUME_BATCH_ID="msgbatch_a,msgbatch_b" — 결과의 custom_id로 장소를
          읽어 반영하고 새 보강은 하지 않는다(재제출·재과금 없음, Anthropic 결과 보관 29일). 그 사이 보강된 행은
          덮지 않고(v2는 속성 버전 기준) 실패 횟수도 올리지 않는다
        ※ recommended_time_slots는 RouteOptimizer.timePreference()가 소비 —
          야간 골프장 같은 시간대 부적합 배치 방지
  [3/4-이름] NameKoOverrideJob.run()  (batch.name-ko-overrides.enabled, 기본 true)
        name-ko-overrides.tsv(키: Wikidata QID 또는 "국가코드:원문 이름")의 한국어 이름으로 name_ko를 덮는다.
        QID 키는 식음료 행에 적용하지 않는다(체인 항목이 여러 지점에 붙어 있다). 체인은 "국가코드:원문 이름"으로 적는다.
        Wikidata 한국어 라벨이 정식명이라 한국 여행자가 쓰는 이름과 다른 곳(로쿠온지 → 금각사)을 적는다.
        표만 고치면 재보강 없이 다음 배치에 반영. 바뀐 행은 embedding=NULL
  [4/4] EmbeddingBatchJob.execute()
        embedding IS NULL AND active=true 인 장소를 100건씩 페이징 조회
        → EmbeddingTextBuilder.buildText() (name+name_ko+category+tags+description+region 결합)
        → EmbeddingService.embedBatch() (OpenAI text-embedding-3-small, 1536차원)
        → PgVectorPlaceSearchService.storeBatch() (JDBC batchUpdate, ::vector 캐스팅)
        ※ 순서 주의: enrich가 임베딩보다 먼저다 — 같은 실행 안에서 보강 내용이 임베딩에 반영되도록.
        ※ 미임베딩이 batch.embedding.ann-rebuild-min-pending(기본 20만) 이상이면 HNSW 인덱스를 내렸다가 끝나고
          다시 만든다(maintenance_work_mem = VECTOR_ANN_BUILD_MEMORY, 기본 1GB — DB 메모리의 1/4 이하로. RDS를
          줄이면 이 값도 줄인다).
          나라 하나 적재는 인덱스를 둔 채 넣어 운영 검색이 느려지지 않는다.
        ※ name_ko가 원문 이름과 같으면 임베딩 텍스트에 넣지 않는다. Wikidata가 name_ko를 채우면 보강이 끝난 행만
          임베딩을 다시 만든다(보강 전인 행은 보강 결과 반영 때 다시 만들어진다)
          OpenAI 429는 retry-after(-ms)만큼 기다려 재시도
```

### 규모와 비용 (2026-10 실측, 한국 12개 도시)

- Foursquare 추출 약 23.8만(영문 체인 제외 후) → 비여행 카테고리 제외 후 약 18.2만(식음료 약 12.5만), TourAPI 약 4,400(법정동 코드 전환 후 증가)
- 첫 실행 약 $13(Haiku 5.5 Batch 단가 $0.05/$0.25 per MTok 기준, count_tokens 실측):
  식음료 약 $7.4(입력 929~1,239토큰·출력 평균 45토큰 × 12.5만) + 그 외 약 $5.5(입력 약 560·출력 약 240토큰 × 6.2만)
  + 임베딩 $0.3 미만. 이후 매달 신규분만 처리해 $2 미만. 보강은 1,000건씩 순차라 첫 실행은 하루 이상 걸릴 수 있다

## 핵심 파일

| 역할 | 파일 |
|---|---|
| 진입점 | `domain/place/batch/BatchJobRunner.java` |
| CSV 시딩 | `domain/place/batch/FoursquareSeeder.java` |
| TourAPI 관광지 시딩 | `domain/place/batch/TourApiSeeder.java` (관광공사 공공데이터, 관광지 커버리지 보완) |
| CSV 소스 추상화 | `domain/place/s3/FoursquareCsvSource.java`, `S3FoursquareCsvReader.java`(prod), 로컬 구현체(local) |
| 시딩 이력 | `domain/place/batch/PlaceSeedingHistory.java` (+ Repository) |
| 임베딩 생성 배치 | `domain/place/batch/EmbeddingBatchJob.java` |
| 임베딩 생성 추상화 | `domain/place/embedding/EmbeddingService.java` (interface) → `OpenAIEmbeddingService.java` (구현체) |
| 임베딩 텍스트 결합 | `domain/place/embedding/EmbeddingTextBuilder.java` |
| 벡터 저장/검색 추상화 | `domain/place/vector/PlaceVectorSearchService.java` (interface) → `PgVectorPlaceSearchService.java` (구현체, JdbcTemplate + pgvector `<=>`) |
| 태그/설명 보강 | `domain/place/batch/BatchEnrichScheduler.java` (Anthropic Batch API 직접 HTTP 호출, SDK 미사용) |
| 마이그레이션 | `db/migration/V19__add_embedding_columns.sql` (embedding 컬럼+HNSW 인덱스), `V23__create_place_seeding_history.sql`, `V24__remove_places_unique_constraint.sql` |
| 인기도 매칭 | `domain/place/batch/WikidataSitelinksJob.java` (Wikidata SPARQL, CC0·무인증), 해외 이름 키 `OverseasNameKeys.java` |
| 대표 명소 적재 | `domain/place/batch/WikidataLandmarkSeeder.java` |
| 해외 관문 적재 | `domain/place/batch/GatewaySeeder.java` |
| 공용 사진 연결 | `domain/place/batch/CommonsImageJob.java` (Wikidata 대표 사진 → 위키미디어 공용 썸네일·저작자·라이선스), `V54__add_places_image_attribution.sql` |
| 해외 여행지 | `src/main/resources/destinations.json` → `domain/place/destination/Destinations.java` (추출기와 같은 파일·배정 규칙) |
| FSQ 추출 | `fsq-extractor/extract.py` (+ `test_extract.py`) |
| 설정 | `application-batch.yml` (`batch.foursquare.chunk-size=1000`, `batch.embedding.batch-size=100`, `batch.enrich.chunk-size=1000`, `batch.enrich.enabled` 기본 false, `anthropic.batch.model=claude-haiku-5-5`) |

## 설계 특징 (검증됨)

- **API 호출과 DB 트랜잭션이 분리되어 있음**: `EmbeddingBatchJob.processBatch()`는 `embedBatch()`(외부 HTTP, 트랜잭션 없음) 호출 후 별도로 `storeBatch()`(`@Transactional`, DB-only batchUpdate)를 호출 — 외부 API 응답 대기 중 DB 커넥션을 잡고 있지 않음.
- **부분 실패 처리는 두 배치가 다름 (2026-07 코드 기준)**:
  - `BatchEnrichScheduler`는 **건별로 처리**한다. `processResults()`가 JSONL 결과를 한 줄씩 파싱해 성공 건은 즉시 `placeRepository.save(place)`로 커밋하고, 실패 건만 `failedIds`에 담아 잔존시킨다. 청크 전체를 버리는 경우는 **제출/폴링/결과조회 HTTP 호출 자체가 실패**해 성공분이 애초에 없는 경우(`ChunkResult(0, size, allIds)`)로 한정된다. 제출·폴링은 `MAX_RETRIES=3` 지수 백오프도 갖췄다.
  - `EmbeddingBatchJob`은 **텍스트 생성 실패는 개별적으로 건너뛰지만**(`textBuildFailedIds`), OpenAI `embedBatch()` API 호출 자체가 (내부 재시도 3회 후에도) 실패하거나 응답 수가 불일치하면 그 배치(≤100건)를 통째로 실패 처리한다. 다음 실행 시 `embedding IS NULL` 조건으로 자동 재시도되므로 **데이터 누락은 없다**. 임베딩 API(text-embedding-3-small)는 단가가 매우 낮아 재요청 비용 영향도 미미하다.
- **동시 실행 보호는 인프라(ECS 스케줄) 레벨에만 의존**: 코드 레벨 분산 락은 없음. `upsertFoursquarePlace`(upsert)와 `embedding IS NULL` 조건 필터 덕분에 두 잡이 겹쳐도 데이터가 깨지진 않지만(idempotent), API 호출이 중복돼 비용이 늘 수 있음. ECS 스케줄이 이전 태스크 종료 전에 재실행되지 않도록 보장하는지는 인프라 설정 확인 필요.
- **SQL Injection 없음**: `PgVectorPlaceSearchService`가 `StringBuilder`로 SQL을 만들지만 모든 가변 값은 `?` 플레이스홀더 + `params.toArray()`로 바인딩됨. `upsertFoursquarePlace`도 `@Param` 네임드 파라미터 사용.
- **인터페이스가 구현체 1개뿐**: `EmbeddingService`/`PlaceVectorSearchService` 모두 구현체가 하나(`OpenAIEmbeddingService`, `PgVectorPlaceSearchService`). 주석에 "향후 Qdrant 등으로 전환 시 구현체만 교체" 의도가 적혀 있음 — 현재 규모에서는 오버엔지니어링일 수 있으나, 외부 API 의존성을 테스트에서 모킹하기 쉽다는 실익은 있음.

## 알려진 개선 후보 (아직 미반영, 우선순위 낮음~중간)

1. `EmbeddingBatchJob`의 `embedBatch()` API 실패 시 청크(≤100건)를 통째로 버리지 말고, 청크를 반으로 재분할해 재시도하거나 정상 응답분만 부분 저장 (재요청 비용 절감). 단, 임베딩 API 단가가 낮아 이득이 작고 회귀 위험이 있어 우선순위 낮음.
2. HNSW 인덱스 파라미터(`m=16, ef_construction=64`, pgvector 기본값)는 장소 데이터가 수십만 건 이상으로 커지면 재튜닝 필요 — 현재 규모에서는 문제 없음.
3. `BatchEnrichScheduler`/`OpenAIEmbeddingService`가 각각 자체 `HttpClient` + 재시도 로직을 들고 있어 코드 중복 — 공통 HTTP 재시도 유틸로 추출 가능.

---
_관련 문서: [`CLAUDE.md`](../../CLAUDE.md) (전체 프로젝트 개요), [`SERVICE_SPECIFICATION.md`](../../SERVICE_SPECIFICATION.md)_
