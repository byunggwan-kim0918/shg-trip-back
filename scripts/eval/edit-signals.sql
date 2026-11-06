-- 생성 후 7일 안에 사용자가 일정을 어떻게 고쳤는지(itinerary_edit_events). 생성 없이 돈다.
-- docker exec -i shg-trip-postgres psql -U weShg -d trip -v since="'2026-11-01'" < scripts/eval/edit-signals.sql
-- 이벤트는 V46 배포 이후에만 있다. 그 전 일정은 since로 걸러 낸다.
-- 대상은 삭제된 것까지 포함한 전체 일정이다(생성 기록은 삭제 시 지워져 기준으로 쓸 수 없다).
-- 7일이 다 지나지 않은 일정은 아직 편집될 수 있어 뺀다.

with g as (
  select i.id, i.destination, i.created_at
  from itineraries i
  where i.created_at >= :since and i.created_at < now() - interval '7 days'
),
e as (
  select g.id, g.destination, ev.type, ev.payload
  from g join itinerary_edit_events ev on ev.itinerary_id = g.id
   and ev.created_at < g.created_at + interval '7 days'
),
per as (
  select g.id, g.destination,
    count(*) filter (where e.type = 'DELETE_STEP') deleted_steps,
    count(*) filter (where e.type = 'REORDER') reorders,
    count(*) filter (where e.type = 'SELECT_ALTERNATIVE') alternatives,
    bool_or(e.type = 'DELETE_ITINERARY') deleted,
    bool_or(e.type = 'FINALIZE') finalized
  from g left join e on e.id = g.id
  group by g.id, g.destination
)
select case when grouping(destination) = 1 then '전체' else destination end region,
  count(*) itineraries,
  round(100.0 * count(*) filter (where deleted_steps > 0) / count(*)) pct_step_deleted,
  round(avg(deleted_steps), 2) avg_deleted_steps,
  round(100.0 * count(*) filter (where reorders > 0) / count(*)) pct_reordered,
  round(100.0 * count(*) filter (where alternatives > 0) / count(*)) pct_alternative,
  round(100.0 * count(*) filter (where deleted) / count(*)) pct_deleted,
  round(100.0 * count(*) filter (where finalized) / count(*)) pct_finalized,
  round(100.0 * count(*) filter (where not coalesce(deleted, false) and deleted_steps = 0 and reorders = 0
        and alternatives = 0) / count(*)) pct_untouched
from per
group by rollup(destination)
order by grouping(destination), destination;

-- 가장 많이 지워진 장소(같은 장소가 반복해서 빠지면 후보·판정 쪽 문제다)
select p.name, p.category, count(*) deletions
from itinerary_edit_events ev join places p on p.id = (ev.payload->>'placeId')::bigint
where ev.type = 'DELETE_STEP' and ev.created_at >= :since
group by p.name, p.category
order by deletions desc
limit 20;
