-- 장소의 인기도 신호를 Wikidata로 옮긴다. Google 리뷰 수를 쓸 수 없게 됐기 때문이다.
--
-- V37로 들여온 user_rating_count는 Google Places의 값이었는데, Google Maps 약관 3.2.3(a)가
-- 장소 세부정보·좌표·주소·평점·사진을 로컬 DB에 캐싱해 재사용하는 것을 금지한다(예외는
-- Place ID 하나뿐). 2026-10-20 지원팀 고지에 따라 Google 유래 데이터 1,237건을 전부 삭제했고,
-- 그 결과 rating·user_rating_count·opening_hours가 전부 0건이 되어 랭킹이 순수 벡터
-- 유사도로 회귀했다.
--
-- 대체 신호는 Wikidata의 sitelinks(해당 항목을 다루는 위키 언어판 수)다. CC0라 저장 제약이
-- 없고, SPARQL 엔드포인트가 인증 없이 무료이며, 지역 bbox 쿼리 1회로 전부 받는다.
--
-- 실측(제주 bbox 748건): 한라산 46, 우도 17, 성산일출봉 천연보호구역 16, 만장굴 11.
-- 랭킹에서 계속 이기던 무명 장소(열안지오름·사라봉)는 Wikidata에 항목 자체가 없다.
-- 해외도 동일하게 작동한다(교토 bbox: 기요미즈데라 77, 로쿠온지 57, 후시미이나리 41).
-- TourAPI는 국내 전용이라 일본 34,033곳을 못 덮는데 Wikidata는 덮는다.
--
-- 매칭은 이름이 아니라 좌표로 한다. Wikidata의 표제가 우리 DB와 다르기 때문이다
-- (예: "성산일출봉 천연보호구역"(33.4581,126.9425) ↔ "성산일출봉 [유네스코 세계자연유산]"
-- (33.4591,126.9396), 약 290m 차이).
--
-- 적재처: WikidataSitelinksJob (batch 프로필)
-- 소비처: PlaceProminence.of → VectorSearchQueryService.hybridScore,
--         PgVectorPlaceSearchService의 인기도 시딩 갈래
ALTER TABLE places ADD COLUMN wikidata_id VARCHAR(16);
ALTER TABLE places ADD COLUMN wikidata_sitelinks INTEGER;
ALTER TABLE places ADD COLUMN wikidata_matched_at TIMESTAMPTZ;

COMMENT ON COLUMN places.wikidata_id IS
  'Wikidata 항목 ID(Q12345). 좌표 매칭으로 찾는다. null은 매칭되는 항목이 없다는 뜻.';
COMMENT ON COLUMN places.wikidata_sitelinks IS
  '그 항목을 다루는 위키 언어판 수. 인기도 신호 — 명소일수록 크고 무명은 항목 자체가 없다.';
COMMENT ON COLUMN places.wikidata_matched_at IS
  '매칭을 시도한 시각. sitelinks가 null인 것이 "아직 안 봤음"인지 "봤는데 없음"인지 구분한다 — 후자는 재조회 대상이 아니다.';

-- 매칭 대상 조회용 부분 인덱스. 배치가 "아직 매칭을 시도하지 않은 활성 장소"를 지역으로
-- 걸러 가져온다. user_rating_count/enriched_at의 기존 부분 인덱스와 같은 패턴.
CREATE INDEX IF NOT EXISTS idx_places_wikidata_unmatched
    ON places(region) WHERE wikidata_matched_at IS NULL AND active = true;
