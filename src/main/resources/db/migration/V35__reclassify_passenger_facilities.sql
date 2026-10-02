-- 여객시설(터미널·대합실·선착장 등)이 관광지 카테고리로 남아 있는 행을 교통허브로 재분류한다.
--
-- V31 ③이 같은 일을 했지만 `source = 'tourapi'` 조건이 붙어 있어, Google 동기화로 source가
-- 'google'로 바뀐 행과 Foursquare 유입분을 놓쳤다. 실측: "성산포항 종합여객터미널"과
-- "제주 우도 천진항 대합실"이 여전히 'Landmarks and Outdoors > Tourist Attraction'이라
-- 일정에 90분짜리 관광 스텝으로 배치됐다.
--
-- 비활성화가 아니라 재분류인 이유는 V31과 동일 — 여객터미널은 도착/출발 허브 후보로는 유효하다.
--
-- ※ 이름이 그냥 '~항'인 항구(미포항·학리항·제주항 등 약 40건)는 **대상이 아니다.**
--    관광지로 유효한 어항이 많아 일괄 재분류하면 실제 명소가 일정에서 사라진다. 그쪽은
--    PlaceCategoryConstants.subType()이 'harbor' 유형을 부여해 반복 배치만 막는다.
UPDATE places
SET category = 'Travel and Transportation > Transport Hub > Terminal'
WHERE active = true
  AND category ILIKE 'Landmarks%'
  AND name ~ '(여객|터미널|대합실|선착장|부두|도선|카페리)';

-- 카테고리는 임베딩 텍스트(EmbeddingTextBuilder)에 포함되므로, 재분류된 행은 임베딩을 비워
-- 다음 배치에서 새 카테고리로 다시 만들게 한다. 리셋하지 않으면 구 카테고리("관광지") 기준
-- 임베딩이 남아 관광 쿼리에 계속 높은 유사도로 잡힌다(V32와 같은 이유).
UPDATE places
SET embedding = NULL
WHERE active = true
  AND category = 'Travel and Transportation > Transport Hub > Terminal'
  AND embedding IS NOT NULL
  AND name ~ '(여객|터미널|대합실|선착장|부두|도선|카페리)';
