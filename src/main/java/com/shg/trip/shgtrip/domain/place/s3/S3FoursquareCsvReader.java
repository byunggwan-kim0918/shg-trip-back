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

@Slf4j
@Component
@RequiredArgsConstructor
public class S3FoursquareCsvReader implements FoursquareCsvReader {

    private final S3Client s3Client;
    private final S3Properties s3Properties;

    private static final String FOURSQUARE_PREFIX = "foursquare/";
    private static final String DATE_PATTERN = "dt=(\\d{4}-\\d{2}-\\d{2})";
    private static final Pattern DATE_REGEX = Pattern.compile(DATE_PATTERN);

    @Override
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public Optional<InputStream> readLatest() {
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
