-- 식당으로 적재된 카페·베이커리를 재분류한다.
--
-- TourAPI 음식점(contentTypeId=39)을 전부 'Dining and Drinking > Restaurant' 하나로 적재해서,
-- 제주 카페 70여 곳이 식당 카테고리에 들어가 있었다(실제 Cafe 카테고리는 7곳, 그중 1곳은 서울).
-- 그 결과 카페 슬롯·카페 스톱·빈 시간 채우기에서 카페가 거의 나오지 않았다(실측 itinerary 115).
--
-- 판정(PlaceCategoryConstants.isCafeLike와 같은 기준):
--   1) 이름에 카페·커피·베이커리·제과·디저트·빵집 신호가 있고,
--      이름이 식당을 말하지 않으며(레스토랑·식당·다이닝·카페리 등),
--      태그가 "식당이고 커피·디저트 신호는 없음"이 아닐 것
--   2) 또는 이름 신호는 없지만 태그에 카페 계열이 있고 식사 계열 태그가 전혀 없을 것
-- 베이커리 신호(이름·태그)가 있으면 Bakery, 아니면 Cafe로 둔다.
--
-- category가 임베딩 텍스트에 들어가므로(EmbeddingTextBuilder) 재분류한 행은 embedding을 비운다 —
-- 비우지 않으면 옛 category로 검색된다(V32와 같은 이유). 다음 임베딩 배치가 다시 채운다.

WITH t AS (
    SELECT id,
           (name ~* '카페|커피|베이커리|제과|디저트|빵집|cafe|coffee|bakery|patisserie') AS name_sig,
           (name ~* '레스토랑|restaurant|dining|식당|kitchen|pasta|뷔페|buffet|카페리|レストラン|tomato') AS restaurant_name,
           EXISTS (SELECT 1 FROM unnest(tags) x WHERE x ~ '카페|디저트|베이커리|제과|커피') AS cafe_tag,
           EXISTS (SELECT 1 FROM unnest(tags) x WHERE x ~ '커피|디저트|베이커리|제과|빵') AS drink_tag,
           EXISTS (SELECT 1 FROM unnest(tags) x WHERE x ~ '한식|레스토랑|다이닝|음식점|식당|해산물') AS restaurant_tag,
           EXISTS (SELECT 1 FROM unnest(tags) x
                   WHERE x ~ '식사|식당|한식|레스토랑|다이닝|맛집|음식|점심|저녁|해산물|요리|횟집|고기') AS meal_tag,
           (name ~* '베이커리|제과|빵집|bakery|patisserie'
               OR EXISTS (SELECT 1 FROM unnest(tags) x WHERE x ~ '베이커리|제과|빵')) AS bakery_sig
    FROM places
    WHERE active
      AND category = 'Dining and Drinking > Restaurant'
),
target AS (
    SELECT id,
           CASE WHEN bakery_sig THEN 'Dining and Drinking > Bakery'
                ELSE 'Dining and Drinking > Cafe, Coffee, and Tea House' END AS new_category
    FROM t
    WHERE (name_sig AND NOT restaurant_name AND NOT (restaurant_tag AND NOT drink_tag))
       OR (NOT name_sig AND cafe_tag AND NOT meal_tag)
)
UPDATE places p
SET category   = target.new_category,
    embedding  = NULL,
    updated_at = now()
FROM target
WHERE p.id = target.id;
