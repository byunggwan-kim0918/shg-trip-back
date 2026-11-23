-- 여행지 사전. 일정 생성에 쓰는 여행지(ACTIVE)와 장소 데이터를 모으는 중(PREPARING), 이름만 아는 후보(CANDIDATE)를 나눈다.
-- destination_key는 국가코드:출처:출처 ID(JP:gn:2128574, KR:curated:Seoul)라 국가가 달라도 같은 이름이 겹치지 않는다.
-- region_key는 places.region 값이다. 장소 데이터가 붙는 여행지(ACTIVE·PREPARING)만 가진다.
CREATE TABLE destinations (
    destination_key   VARCHAR(64)   PRIMARY KEY,
    country           CHAR(2)       NOT NULL,
    kind              VARCHAR(16)   NOT NULL,
    name              VARCHAR(255)  NOT NULL,
    name_ko           VARCHAR(255),
    alias_keys        TEXT[]        NOT NULL DEFAULT '{}',
    latitude          NUMERIC(10,8) NOT NULL,
    longitude         NUMERIC(11,8) NOT NULL,
    radius_km         NUMERIC(6,2),
    population        INTEGER       NOT NULL DEFAULT 0,
    parent_key        VARCHAR(64),
    region_key        VARCHAR(255),
    status            VARCHAR(12)   NOT NULL CHECK (status IN ('ACTIVE', 'PREPARING', 'CANDIDATE')),
    source            VARCHAR(16)   NOT NULL,
    source_id         VARCHAR(64),
    requested_count   INTEGER       NOT NULL DEFAULT 0,
    last_requested_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CHECK ((region_key IS NULL) = (status = 'CANDIDATE'))
);

-- alias_keys는 이름·한국어 이름·별칭의 place_name_key다. 여행자가 친 여행지 이름을 한 번의 인덱스 조회로 찾는다.
CREATE INDEX idx_destinations_alias_keys ON destinations USING gin (alias_keys);
CREATE UNIQUE INDEX uq_destinations_region_key ON destinations (region_key) WHERE region_key IS NOT NULL;
CREATE INDEX idx_destinations_requested ON destinations (requested_count DESC) WHERE status <> 'ACTIVE';
