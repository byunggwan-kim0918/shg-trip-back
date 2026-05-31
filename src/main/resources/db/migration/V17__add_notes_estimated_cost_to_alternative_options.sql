-- alternative_options에 notes, estimated_cost 컬럼 추가
-- 대안 선택 시 해당 장소의 설명과 예상 비용을 함께 교체하기 위함
ALTER TABLE alternative_options
    ADD COLUMN IF NOT EXISTS notes TEXT,
    ADD COLUMN IF NOT EXISTS estimated_cost NUMERIC(10, 2);
