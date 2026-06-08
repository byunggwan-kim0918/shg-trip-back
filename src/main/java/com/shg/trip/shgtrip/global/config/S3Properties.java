package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AWS S3 설정.
 * application.yml의 cloud.aws.s3 하위 프로퍼티를 바인딩합니다.
 */
@ConfigurationProperties(prefix = "cloud.aws.s3")
public record S3Properties(
        String region,
        String bucket,
        String accessKey,
        String secretKey
) {
}
