package com.shg.trip.shgtrip.domain.place.s3;

import java.util.Optional;

/**
 * Google Places 이미지를 S3에 업로드하는 인터페이스.
 * 이미 존재하면 스킵하며, 실패해도 기존 photoReference는 유지된다.
 */
public interface PlaceImageUploader {
    /**
     * 이미지를 S3에 업로드한다. 이미 존재하면 스킵한다.
     * @param placeId Place의 DB ID
     * @param photoReference Google Places photo reference (full resource name)
     * @return 업로드된 S3 presigned URL, 스킵 시 기존 URL, 실패 시 Optional.empty()
     */
    Optional<String> uploadIfAbsent(Long placeId, String photoReference);

    /**
     * S3에 이미 존재하는 객체의 presigned URL을 생성한다.
     * Google API 호출 없이 로컬에서 서명만 수행한다 (무료).
     * @param key S3 객체 키 (예: "images/places/123.jpg")
     * @return presigned URL (7일 유효), 실패 시 Optional.empty()
     */
    Optional<String> generatePresignedUrlForKey(String key);
}
