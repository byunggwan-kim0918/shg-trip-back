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
     * @return 업로드된 S3 URL, 스킵 시 기존 URL, 실패 시 Optional.empty()
     */
    Optional<String> uploadIfAbsent(Long placeId, String photoReference);
}
