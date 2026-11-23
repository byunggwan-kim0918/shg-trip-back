-- 장소 이름 정확 키(NFKC·소문자 뒤 글자·숫자만). Java PlaceNames.exactKey·place-pipeline names.exact_key와 같은 규칙이다
-- (계약 src/test/resources/place-names/exact-keys.tsv, PlaceNameKeyContractIntegrationTest).
-- 쿼리마다 식을 따로 적으면 규칙이 갈라져 같은 이름이 경로에 따라 잡히기도 하고 안 잡히기도 한다.
-- [:alnum:]은 DB lc_ctype(en_US.UTF-8)의 분류를 따른다. C 로케일이면 ASCII만 글자로 봐 한글·한자 키가 빈 문자열이 된다.
CREATE OR REPLACE FUNCTION place_name_key(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$
SELECT regexp_replace(lower(normalize($1, NFKC)), '[^[:alnum:]]', '', 'g')
$$;
