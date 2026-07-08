-- google_synced_at backfill: V20에서 컬럼만 추가되어 기존 행이 전부 NULL.
-- findByIdAndNeedsSync 조건이 (googleSyncedAt IS NULL OR < 7일전)으로 바뀌면서
-- 이미 Google 동기화가 끝난 장소(source='google')까지 재동기화 대상이 되는 폭주 방지.
-- foursquare 행은 NULL 유지 — 기존 쿼리(source='foursquare')와 동일하게 동기화 대상으로 남긴다.
UPDATE places
SET google_synced_at = saved_at
WHERE google_synced_at IS NULL
  AND source = 'google';
