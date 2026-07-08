-- Google place_id 저장 컬럼 추가.
-- refresh를 Text Search(검색, $40/1K)가 아닌 Place Details(New, ID 직조회 $17~20/1K)로
-- 전환하기 위해 첫 매칭 시 Google이 반환한 place_id를 저장한다. 이후 재조회는 이 ID로 직조회.
-- 유니크 제약은 걸지 않는다: 오매칭 병합 방지 + NULL(미매칭/fallback) 다수 허용.
ALTER TABLE places ADD COLUMN IF NOT EXISTS google_place_id VARCHAR(255);

-- 월배치 사전채움(GooglePlaceSyncScheduler)의 인기도 기준.
-- 벡터 검색 후보로 뽑힐 때마다 갱신 → "최근 후보 등장 = 인기 장소"를 실측 기반으로 판정한다.
ALTER TABLE places ADD COLUMN IF NOT EXISTS last_candidate_at TIMESTAMPTZ;

-- place_id 직조회 성능용 부분 인덱스 (NULL 다수 제외).
CREATE INDEX IF NOT EXISTS idx_places_google_place_id
    ON places(google_place_id) WHERE google_place_id IS NOT NULL;
