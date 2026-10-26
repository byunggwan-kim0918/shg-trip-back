-- 장소의 리뷰 수(인기도 신호). 벡터 검색 랭킹이 "갈 만한 곳"을 가리기 위해 필요하다.
--
-- 지금까지 인기도에 해당하는 신호가 테이블에 전혀 없었다. 랭킹은 평점(rating)만 보는데
-- 평점 보유율이 지역별로 0.6~24%뿐이고, 평점이 없으면 중립값(0.7)으로 처리된다.
-- 그 결과 리뷰 두세 개짜리 5.0이 수만 리뷰의 4.7을 이긴다.
--
-- 실측(제주 관광지를 평점순 정렬): 용두암 5.0, 쉼한모금 5.0, 사라봉공원 5.0, 논짓물 4.8,
-- 1100고지습지 4.8 ... 협재해수욕장(4.7)이 11위. 성산일출봉·쇠소깍·산굼부리·섭지코지·
-- 천지연폭포는 TourAPI 적재분이라 평점이 아예 없어 순위에 등장조차 못 한다.
--
-- Google Places API (New)의 userRatingCount로 채운다. rating과 같은 Enterprise SKU 등급이고
-- 재동기화 마스크에 이미 rating이 있으므로 이 필드를 추가해도 과금 등급이 바뀌지 않는다.
--
-- 소비처: VectorSearchQueryService.hybridScore (평점 항을 rating × log10(1+reviewCount)로 교체)
-- 적재처: Place.update (GooglePlaceSyncScheduler / PlaceRefreshService 경로)
--
-- nullable이라 기존 행에 영향 없음. 백필 전에는 null이고, 랭킹은 null을 "인기도 미상"으로 다룬다.
ALTER TABLE places ADD COLUMN user_rating_count INTEGER;

COMMENT ON COLUMN places.user_rating_count IS
  'Google 리뷰 수(userRatingCount). 인기도 신호 — rating 단독으로는 리뷰 2개짜리 5.0이 명소를 이긴다. null은 미상.';

-- 백필 대상 조회용 부분 인덱스. GooglePlaceSyncScheduler가 "리뷰 수 없는 활성 장소"를
-- 지역·카테고리로 걸러 가져온다. enriched_at/last_candidate_at의 기존 부분 인덱스와 같은 패턴.
CREATE INDEX IF NOT EXISTS idx_places_user_rating_count_null
    ON places(region, category) WHERE user_rating_count IS NULL AND active = true;
