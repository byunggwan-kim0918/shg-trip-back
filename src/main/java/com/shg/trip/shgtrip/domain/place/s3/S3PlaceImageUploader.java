package com.shg.trip.shgtrip.domain.place.s3;

import com.shg.trip.shgtrip.domain.place.client.GooglePlacesClient;
import com.shg.trip.shgtrip.global.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;
import java.util.Optional;

/**
 * Google Places API로 이미지를 다운로드한 뒤 S3에 업로드하는 구현체.
 * S3에 이미 객체가 존재하면 업로드를 건너뛰고 기존 URL을 반환한다(멱등성 보장).
 * 업로드 실패 시 WARN 로그를 남기고 Optional.empty()를 반환하여 파이프라인을 중단시키지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3PlaceImageUploader implements PlaceImageUploader {

    private static final String S3_IMAGE_KEY_PREFIX = "images/places/";
    private static final String S3_IMAGE_EXTENSION = ".jpg";
    private static final int PRESIGNED_URL_DURATION_DAYS = 7;

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;
    private final GooglePlacesClient googlePlacesClient;

    @Override
    public Optional<String> uploadIfAbsent(Long placeId, String photoReference) {
        String key = buildS3ImageKey(placeId);

        // 1) S3에 이미 존재하면 스킵 — Presigned URL 생성해서 반환
        if (existsInS3(key)) {
            log.debug("S3 이미지 이미 존재, presigned URL 생성: key={}", key);
            return Optional.of(buildPresignedUrl(key));
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

            // 4) Presigned URL 생성해서 반환
            return Optional.of(buildPresignedUrl(key));
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

    /**
     * S3 객체의 Presigned URL을 생성한다 (7일 유효).
     * Presigned URL은 AWS 서명이 포함된 임시 접근 URL이며, S3 버킷이 private이어도 접근 가능하다.
     * 7일 유효으로 설정하여 사용자가 저장된 일정을 보는 동안 이미지가 만료되지 않도록 보장.
     */
    private String buildPresignedUrl(String key) {
        return generatePresignedUrl(key);
    }

    private String generatePresignedUrl(String key) {
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(s3Properties.bucket())
                    .key(key)
                    .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofDays(PRESIGNED_URL_DURATION_DAYS))
                    .getObjectRequest(getObjectRequest)
                    .build();

            return s3Presigner.presignGetObject(presignRequest)
                    .url()
                    .toString();
        } catch (Exception e) {
            log.warn("Presigned URL 생성 실패: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public Optional<String> generatePresignedUrlForKey(String key) {
        String presignedUrl = generatePresignedUrl(key);
        if (presignedUrl != null) {
            return Optional.of(presignedUrl);
        }
        return Optional.empty();
    }

    private String buildS3ImageKey(Long placeId) {
        return S3_IMAGE_KEY_PREFIX + placeId + S3_IMAGE_EXTENSION;
    }
}
