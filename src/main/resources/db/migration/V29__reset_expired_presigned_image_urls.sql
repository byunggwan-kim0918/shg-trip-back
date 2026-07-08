-- presigned URL(만료·주기 갱신) → CloudFront/S3(OAC) 정적 URL 전환에 따른 일회성 데이터 정리.
--
-- 배경: 기존 places.image_url에는 만료된 7일 presigned URL 문자열이 저장돼 있다.
-- 모든 이미지 자가치유 경로(PlaceImageAsyncRecovery, ItineraryService 커버 복구,
-- PlaceRefreshService.uploadPhotoIfAbsent)의 트리거 조건이 image_url IS NULL 이므로,
-- non-null 옛 presigned가 남아 있으면 자가치유가 발동하지 않아 영구히 깨진 채 남는다.
--
-- 조치: presigned 서명 쿼리(X-Amz-Signature)가 포함된 image_url을 NULL로 리셋한다.
-- 다음 조회 시 자가치유 경로가 만료 없는 정적 URL을 다시 채운다(photo_reference 필요).
-- 새 정적 URL(https://{domain}/images/places/{id}.jpg)에는 X-Amz-Signature가 없으므로
-- 이번에 새로 저장된 값은 영향을 받지 않는다.
UPDATE places
SET image_url = NULL
WHERE image_url LIKE '%X-Amz-Signature%';
