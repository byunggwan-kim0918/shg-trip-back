package com.shg.trip.shgtrip.domain.place.s3;

import com.shg.trip.shgtrip.global.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S3 버킷에서 최신 Foursquare CSV 파일의 InputStream을 읽어오는 구현체.
 * foursquare/dt={yyyy-MM-dd}/ 파티션 구조에서 가장 최신 날짜를 선택하여 InputStream을 반환한다.
 * 네트워크 오류 발생 시 최대 3회 재시도한다(Spring Retry).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3FoursquareCsvReader implements FoursquareCsvReader {

    private final S3Client s3Client;
    private final S3Properties s3Properties;

    private static final String FOURSQUARE_PREFIX = "foursquare/";
    private static final String DATE_PATTERN = "dt=(\\d{4}-\\d{2}-\\d{2})";
    private static final Pattern DATE_REGEX = Pattern.compile(DATE_PATTERN);

    /**
     * S3에서 최신 날짜 파티션의 Foursquare CSV InputStream을 반환한다.
     * 파일이 없으면 WARN 로그 후 Optional.empty() 반환.
     * 네트워크 오류 시 최대 3회 재시도.
     *
     * @return 최신 CSV 파일의 InputStream, 없으면 Optional.empty()
     */
    @Override
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public Optional<InputStream> readLatest() {
        // 1. foursquare/ prefix 하위 객체 목록 조회
        ListObjectsV2Response response = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                        .bucket(s3Properties.bucket())
                        .prefix(FOURSQUARE_PREFIX)
                        .build()
        );

        List<S3Object> objects = response.contents();
        if (objects.isEmpty()) {
            log.warn("S3에 Foursquare CSV 파일이 없습니다: bucket={}, prefix={}",
                    s3Properties.bucket(), FOURSQUARE_PREFIX);
            return Optional.empty();
        }

        // 2. 키에서 dt=yyyy-MM-dd 파싱 후 최신 날짜 파티션 선택
        Optional<String> latestKey = objects.stream()
                .map(S3Object::key)
                .filter(key -> key.endsWith(".csv"))
                .max(Comparator.comparing(key -> {
                    Matcher m = DATE_REGEX.matcher(key);
                    return m.find() ? LocalDate.parse(m.group(1)) : LocalDate.MIN;
                }));

        if (latestKey.isEmpty()) {
            log.warn("S3에서 유효한 날짜 파티션 CSV를 찾을 수 없습니다: bucket={}, prefix={}",
                    s3Properties.bucket(), FOURSQUARE_PREFIX);
            return Optional.empty();
        }

        // 3. 최신 파일의 InputStream 반환
        log.info("S3 Foursquare CSV 읽기: bucket={}, key={}", s3Properties.bucket(), latestKey.get());
        software.amazon.awssdk.core.ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(
                GetObjectRequest.builder()
                        .bucket(s3Properties.bucket())
                        .key(latestKey.get())
                        .build()
        );
        return Optional.of(stream);
    }
}
