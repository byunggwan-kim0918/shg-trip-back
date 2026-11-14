-- 국내 장소 데이터 감사. 읽기 전용이다.
-- docker exec -i shg-trip-postgres psql -U weShg -d trip < scripts/eval/region-mismatch-audit.sql
-- 광역 표기와 지역별 허용 범위는 KoreanAddressRegion과 같아야 한다. 한쪽을 바꾸면 다른 쪽도 바꾼다.

-- 1. 주소의 광역 단위가 region과 어긋나는 활성 장소. 지역별 건수와 언어판 많은 순 예시 10곳
with province(token, province) as (
  values ('서울','서울'),('서울시','서울'),('서울특별시','서울'),
         ('부산','부산'),('부산시','부산'),('부산광역시','부산'),
         ('대구','대구'),('대구시','대구'),('대구광역시','대구'),
         ('인천','인천'),('인천시','인천'),('인천광역시','인천'),
         ('광주','광주'),('광주광역시','광주'),
         ('대전','대전'),('대전시','대전'),('대전광역시','대전'),
         ('울산','울산'),('울산시','울산'),('울산광역시','울산'),
         ('세종','세종'),('세종시','세종'),('세종특별자치시','세종'),
         ('경기','경기'),('경기도','경기'),
         ('강원','강원'),('강원도','강원'),('강원특별자치도','강원'),
         ('충북','충북'),('충청북도','충북'),
         ('충남','충남'),('충청남도','충남'),
         ('전북','전북'),('전라북도','전북'),('전북특별자치도','전북'),
         ('전남','전남'),('전라남도','전남'),
         ('전남광주','전남광주'),('전남광주통합특별시','전남광주'),
         ('경북','경북'),('경상북도','경북'),
         ('경남','경남'),('경상남도','경남'),
         ('제주','제주'),('제주도','제주'),('제주특별자치도','제주'),
         ('제주시','제주'),('서귀포시','제주')
),
allowed(region, province) as (
  values ('Seoul','서울'),('Seoul','경기'),('Seoul','인천'),
         ('Busan','부산'),('Busan','경남'),('Busan','울산'),
         ('Jeju','제주'),
         ('Incheon','인천'),('Incheon','경기'),('Incheon','서울'),
         ('Daegu','대구'),('Daegu','경북'),
         ('Daejeon','대전'),('Daejeon','충남'),('Daejeon','충북'),('Daejeon','세종'),
         ('Suwon','경기'),('Suwon','서울'),
         ('Gangneung','강원'),
         ('Gwangju','광주'),('Gwangju','전남'),('Gwangju','전남광주'),
         ('Gyeongju','경북'),('Gyeongju','울산'),
         ('Jeonju','전북'),
         ('Yeosu','전남'),('Yeosu','전남광주'),('Yeosu','경남')
),
mismatch as (
  select pl.id, pl.name, pl.region, pr.province address_province, pl.place_role, pl.address,
         count(*) over (partition by pl.region) region_mismatches,
         row_number() over (partition by pl.region order by pl.wikidata_sitelinks desc nulls last, pl.id) rk
  from places pl
  join province pr on pr.token = (regexp_split_to_array(btrim(pl.address), '\s+'))[1]
  where pl.active and pl.region in (select region from allowed)
    and not exists (select 1 from allowed a where a.region = pl.region and a.province = pr.province)
)
select region, region_mismatches, id, name, address_province, place_role, address
from mismatch where rk <= 10 order by region_mismatches desc, region, rk;

-- 2. 국내 장소 설명의 한자·가나. mixed_in_word는 한글 낱말에 붙은 것(보강 반영 시 거르는 기준)이다.
--    보강은 장소 이름에 든 한자·가나를 빼고 보므로 이 수보다 적게 거른다.
select count(*) filter (where description ~ '[가-힣][㐀-䶿一-鿿ぁ-ゟァ-ヺヽ-ヿ]|[㐀-䶿一-鿿ぁ-ゟァ-ヺヽ-ヿ][가-힣]') mixed_in_word,
       count(*) any_cjk
from places
where active and country = 'KR' and description ~ '[㐀-䶿一-鿿ぁ-ゟァ-ヺヽ-ヿ]';
