-- 생성 기록을 EvalRunner --eval.mode=files가 읽는 TraceFile JSON 줄로 뽑는다. 읽기 전용.
-- 요청 문장(request·enriched_input)은 담지 않는다. 다른 사용자의 기록이 섞이지 않게 계정을 지정한다.
--
-- psql -At -v users="1,7" < scripts/eval/export-traces.sql > traces.jsonl
SELECT json_build_object(
    'itineraryId', t.itinerary_id,
    'destination', i.destination,
    'startDate', i.start_date,
    'endDate', i.end_date,
    'searchPoolSize', t.search_pool_size,
    'candidates', t.candidates,
    'selection', t.selection_final,
    'scheduleArgs', t.schedule_args,
    'savedSteps', (
        SELECT coalesce(json_agg(json_build_object(
                   'stepOrder', s.step_order,
                   'dayNumber', s.day_number,
                   'startTime', s.start_time,
                   'endTime', s.end_time,
                   'transportationMode', s.transportation_mode,
                   'transportationDuration', s.transportation_duration,
                   'transportationDistance', s.transportation_distance,
                   'place', json_build_object(
                       'placeId', p.id, 'name', p.name, 'address', p.address, 'category', p.category,
                       'region', p.region, 'country', p.country, 'nameKo', p.name_ko)
               ) ORDER BY s.day_number, s.step_order), '[]'::json)
        FROM itinerary_steps s
        JOIN places p ON p.id = s.place_id
        WHERE s.itinerary_id = i.id))
FROM generation_traces t
JOIN itineraries i ON i.id = t.itinerary_id
WHERE i.deleted_at IS NULL AND i.user_id IN (:users)
ORDER BY t.itinerary_id;
