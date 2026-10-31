-- 평가 세트 생성 기록(generation_traces)의 후보 풀 점검. 생성 없이 돈다.
-- docker exec -i shg-trip-postgres psql -U weShg -d trip -v lo=182 -v hi=201 < scripts/eval/pool-audit.sql

-- 1. 지역별 풀 구성(%): 숙소 · 식음료 · 교통허브 · 일반 상점 · 비방문 · 인기 명소(sitelinks 3+)
with c as (
  select t.itinerary_id, i.destination, x.c->>'category' cat, (x.c->>'wikidataSitelinks')::int sl
  from generation_traces t join itineraries i on i.id = t.itinerary_id
  cross join lateral jsonb_array_elements(t.candidates) x(c)
  where t.itinerary_id between :lo and :hi
)
select destination,
  count(*) / count(distinct itinerary_id) avg_pool,
  round(100.0 * count(*) filter (where cat ilike '%lodging%') / count(*)) lodging,
  round(100.0 * count(*) filter (where cat ilike 'Dining%') / count(*)) dining,
  round(100.0 * count(*) filter (where cat ilike '%transport hub%' or cat ilike '%airport%') / count(*)) hub,
  round(100.0 * count(*) filter (where cat ilike 'Retail%' and cat not ilike '%market%'
        and cat not ilike '%mall%' and cat not ilike '%department%') / count(*)) plain_retail,
  round(100.0 * count(*) filter (where cat ilike 'Business%' or cat ilike 'Health%'
        or (cat ilike 'Community and Government%' and cat not ilike '%spiritual%')) / count(*)) non_visit,
  round(100.0 * count(*) filter (where sl >= 3) / count(*)) iconic
from c group by 1 order by 2 desc;

-- 2. 지역 인기 상위 10곳(위키데이터 항목 기준) 중 후보에 든 수와 빠진 곳.
--    같은 장소가 출처별로 여러 행이라 장소 id로 비교하면 틀린다.
with dest(destination, region) as (
  values ('제주','Jeju'),('서울','Seoul'),('부산','Busan'),('경주','Gyeongju'),
         ('오사카','Osaka'),('교토','Kyoto'),('도쿄','Tokyo')
),
top as (
  select destination, wikidata_id, name, sl,
         row_number() over (partition by destination order by sl desc) rk
  from (
    select distinct on (d.destination, p.wikidata_id) d.destination, p.wikidata_id, p.name, p.wikidata_sitelinks sl
    from dest d join places p on p.region = d.region and p.active and p.wikidata_sitelinks > 0
      and p.wikidata_id is not null
      and (p.place_role is null or p.place_role in ('SIGHT','ACTIVITY','MARKET','SHOPPING'))
      and p.category not ilike '%transport%' and p.category not ilike '%lodging%'
      and p.category not ilike 'Dining%' and p.category not ilike '%education%'
      and p.category not ilike '%government%' and p.category not ilike '%library%'
    order by d.destination, p.wikidata_id, p.wikidata_sitelinks desc
  ) u
),
pool as (
  select distinct i.destination, p.wikidata_id
  from generation_traces t join itineraries i on i.id = t.itinerary_id
  cross join lateral jsonb_array_elements(t.candidates) x(c)
  join places p on p.id = (x.c->>'placeId')::bigint
  where t.itinerary_id between :lo and :hi and p.wikidata_id is not null
)
select t.destination, count(*) filter (where p.wikidata_id is not null) in_pool_of_10,
  string_agg(case when p.wikidata_id is null then t.name || '(' || t.sl || ')' end, ', ' order by t.rk) missing
from top t left join pool p on p.destination = t.destination and p.wikidata_id = t.wikidata_id
where t.rk <= 10 group by 1 order by 1;

-- 3. 한 풀 안에 같은 위키데이터 항목이 두 번 이상 든 경우
select t.itinerary_id, p.wikidata_id, string_agg(p.name, ' / ') names
from generation_traces t cross join lateral jsonb_array_elements(t.candidates) x(c)
join places p on p.id = (x.c->>'placeId')::bigint
where t.itinerary_id between :lo and :hi and p.wikidata_id is not null
group by 1, 2 having count(*) > 1 order by 1;
