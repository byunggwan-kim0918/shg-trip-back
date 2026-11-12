-- 해외 장소 보강이 판단한 한국인 여행자 기준 알려진 정도. Wikidata 언어판 수가 거의 없는 식당·야시장의 인기도 대용이다.
-- ADD COLUMN(기본값 없음)은 메타데이터만 바꾸지만 ACCESS EXCLUSIVE 잠금을 잡아 잠금 대기를 짧게 끊는다.
SET LOCAL lock_timeout = '5s';

ALTER TABLE places ADD COLUMN traveler_fame SMALLINT;

ALTER TABLE places
    ADD CONSTRAINT chk_places_traveler_fame CHECK (traveler_fame BETWEEN 0 AND 2) NOT VALID;

COMMENT ON COLUMN places.traveler_fame IS
  '0: 그 밖 | 1: 현지에서 이름난 곳 | 2: 가이드북·미쉐린급. 보강 전·한국 행은 NULL(모름)';
