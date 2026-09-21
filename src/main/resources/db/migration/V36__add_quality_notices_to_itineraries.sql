-- 일정 생성 시 끝내 해소하지 못한 품질 문제(숙소 미배정·식사 누락 등)를 사용자에게 보여주기 위한 컬럼.
--
-- 지금까지는 위반을 로그로만 남겨서, 숙소가 하나도 없는 일정이 아무 표시 없이 사용자에게 나갔다
-- (실측: itinerary 60·66·67). 생성 중 SSE로 알리는 것만으로는 새로고침·나중 조회 때 사라지므로
-- 일정에 함께 저장한다.
--
-- tags와 같은 TEXT[] 패턴. nullable이라 기존 행에 영향 없음.
ALTER TABLE itineraries ADD COLUMN quality_notices TEXT[];

COMMENT ON COLUMN itineraries.quality_notices IS
  '생성 시 해소하지 못한 품질 문제의 사용자 안내 문구. 비어 있으면 모든 구조적 불변식 충족.';
