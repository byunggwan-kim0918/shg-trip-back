package com.shg.trip.shgtrip.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cloud.aws.s3")
public record S3Properties(
        String region,
        String bucket,
        String accessKey,
        String secretKey,
        String cloudFrontDomain
) {
}
