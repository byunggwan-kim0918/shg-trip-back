package com.shg.trip.shgtrip.domain.place.s3;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * batch.foursquare.source 설정에 따라 FoursquareCsvSource 빈을 분기 등록한다.
 * - source=s3   → S3CsvSource
 * - source=local (기본값) → LocalFileCsvSource
 */
@Configuration
public class CsvSourceConfig {

    @Bean
    @ConditionalOnProperty(name = "batch.foursquare.source", havingValue = "s3")
    public FoursquareCsvSource s3CsvSource(FoursquareCsvReader foursquareCsvReader) {
        return new S3CsvSource(foursquareCsvReader);
    }

    @Bean
    @ConditionalOnProperty(name = "batch.foursquare.source", havingValue = "local", matchIfMissing = true)
    public FoursquareCsvSource localFileCsvSource() {
        return new LocalFileCsvSource();
    }
}
