package com.shg.trip.shgtrip.domain.place.s3;

import com.shg.trip.shgtrip.domain.place.client.GooglePlacesClient;
import com.shg.trip.shgtrip.global.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Optional;

/**
 * Google Places API로 이미지를 다운로드한 뒤 S3에 업로드하는 구현체.
 * S3에 이미 객체가 존재하면 업로드를 건너뛰고 기존 URL을 반환한다(멱등성 보장).
 * 업로드 실패 시 WARN 로그를 남기고 Optional.empty()를 반환하여 파이프라인을 중단시키지 않는다.
 *
 * 반환 URL은 {@link ImageUrlResolver}가 생성하는 정적(만료 없는) URL이다
 * — 운영은 CloudFront, 로컬은 LocalStack path-style. presigned URL은 사용하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3PlaceImageUploader implements PlaceImageUploader {

    private static final String S3_IMAGE_KEY_PREFIX = "images/places/";
    private static final String S3_IMAGE_EXTENSION = ".jpg";

    private final S3Client s3Client;
    private final S3Properties s3Properties;
    private final GooglePlacesClient googlePlacesClient;
    private final ImageUrlResolver imageUrlResolver;

    @Override
    public Optional<String> uploadIfAbsent(Long placeId, String photoReference) {
        String key = buildS3ImageKey(placeId);

        // 1) S3에 이미 존재하면 스킵 — 정적 URL 반환
        if (existsInS3(key)) {
            log.debug("S3 이미지 이미 존재, 정적 URL 반환: key={}", key);
            return Optional.of(imageUrlResolver.resolve(placeId));
        }

        // 2) Google Places Photo API로 이미지 바이너리 다운로드
        Optional<byte[]> imageBytes = googlePlacesClient.downloadPhotoBytes(photoReference);
        if (imageBytes.isEmpty()) {
            log.warn("Google Places 이미지 다운로드 실패: placeId={}, photoReference={}", placeId, photoReference);
            return Optional.empty();
        }

        // 3) S3 업로드 — Content-Type: image/jpeg, Cache-Control: max-age=2592000
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(s3Properties.bucket())
                    .key(key)
                    .contentType("image/jpeg")
                    .cacheControl("max-age=2592000")
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(imageBytes.get()));
            log.info("S3 이미지 업로드 완료: placeId={}", placeId);

            // 4) 정적 URL 반환 (만료 없음)
            return Optional.of(imageUrlResolver.resolve(placeId));
        } catch (SdkClientException | NoSuchKeyException e) {
            log.warn("S3 이미지 업로드 실패: placeId={}, error={}", placeId, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("S3 이미지 업로드 실패 (알 수 없는 오류): placeId={}, error={}", placeId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * S3에 해당 키의 객체가 존재하는지 확인한다.
     * HeadObject 요청으로 메타데이터만 조회하여 비용을 최소화한다.
     */
    private boolean existsInS3(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(s3Properties.bucket())
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private String buildS3ImageKey(Long placeId) {
        return S3_IMAGE_KEY_PREFIX + placeId + S3_IMAGE_EXTENSION;
    }
}
