-- places 열 추가·제약은 ACCESS EXCLUSIVE를 잡아 앞선 긴 쿼리를 기다리며 places 읽기를 줄 세우지 않도록 잠금 대기를 짧게 끊는다(실패하면 배포를 다시 한다).
-- 잡은 잠금은 커밋까지 가므로 이 파일에는 테이블을 훑는 문장(인덱스 생성, 제약 검사)을 두지 않는다.
SET LOCAL lock_timeout = '5s';

-- 가까운 두 장소 행의 관계 판정. 입력 지문이 같은 쌍은 다시 묻지 않는다. basis는 RULE(이름 표기만 다름) 또는 LLM이다.
CREATE TABLE place_pair_verdicts (
    low_id    BIGINT      NOT NULL REFERENCES places (id) ON DELETE CASCADE,
    high_id   BIGINT      NOT NULL REFERENCES places (id) ON DELETE CASCADE,
    relation  VARCHAR(12) NOT NULL,
    basis     VARCHAR(8)  NOT NULL,
    input_key VARCHAR(16) NOT NULL,
    judged_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (low_id, high_id),
    CONSTRAINT chk_place_pair_order CHECK (low_id < high_id),
    CONSTRAINT chk_place_pair_relation CHECK (relation IN ('SAME', 'LOW_IN_HIGH', 'HIGH_IN_LOW', 'DIFFERENT')),
    CONSTRAINT chk_place_pair_basis CHECK (basis IN ('RULE', 'LLM'))
);
CREATE INDEX idx_place_pair_verdicts_high ON place_pair_verdicts (high_id);

-- 같은 곳의 대표 행(SAME) 또는 이 행이 딸린 큰 장소(PART). 가리키는 행은 늘 merged_into_id가 비어 있다.
ALTER TABLE places ADD COLUMN merged_into_id BIGINT;
ALTER TABLE places ADD COLUMN merge_relation VARCHAR(8);
-- 기존 행은 모두 NULL이라 검사할 것이 없다. NOT VALID로 테이블 스캔 없이 새 값만 막는다.
ALTER TABLE places ADD CONSTRAINT fk_places_merged_into
    FOREIGN KEY (merged_into_id) REFERENCES places (id) NOT VALID;
ALTER TABLE places ADD CONSTRAINT chk_places_merge_relation
    CHECK ((merged_into_id IS NULL) = (merge_relation IS NULL)
           AND (merge_relation IS NULL OR merge_relation IN ('SAME', 'PART'))
           AND (merged_into_id IS NULL OR merged_into_id <> id)) NOT VALID;
