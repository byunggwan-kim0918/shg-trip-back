-- 업종 카테고리가 양식인데 KOREAN_SET·OTHER로 남은 행. DiningEnrichment.cuisineOf의 양식 카테고리 목록과 같다.
UPDATE places
SET cuisine = 'WESTERN'
WHERE cuisine IN ('KOREAN_SET', 'OTHER')
  AND (category ILIKE '%american%' OR category ILIKE '%salad%' OR category ILIKE '%sandwich%'
       OR category ILIKE '%bistro%' OR category ILIKE '%diner%' OR category ILIKE '%brunch%');
