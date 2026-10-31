-- 후보 검색의 인기도 갈래(region 필터 + wikidata_sitelinks 내림차순)가 places 전체를 병렬 순차 스캔했다.
-- 서울 기준 버퍼 약 15,000개를 읽어 캐시가 식으면 수 초가 걸린다. 인기도가 있는 행만 담는 부분 인덱스로 바꾼다.
CREATE INDEX idx_places_region_sitelinks
    ON places (region, wikidata_sitelinks DESC)
    WHERE active AND wikidata_sitelinks > 0;
