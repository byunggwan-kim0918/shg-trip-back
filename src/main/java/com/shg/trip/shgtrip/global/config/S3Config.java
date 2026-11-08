package com.shg.trip.shgtrip.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

@Slf4j
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    @Value("${cloud.aws.s3.endpoint:}")
    private String s3Endpoint;

    @Bean
    @Profile("local")
    public S3Client s3ClientLocal(S3Properties props) {
        log.info("S3Client (local) 초기화: region={}, bucket={}, endpoint={}",
                 props.region(), props.bucket(), s3Endpoint);
        try {
            var builder = S3Client.builder()
                    .region(Region.of(props.region()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(props.accessKey(), props.secretKey())));

            // LocalStack은 엔드포인트 오버라이드와 Path Style Access가 있어야 붙는다.
            if (StringUtils.hasText(s3Endpoint)) {
                builder.endpointOverride(URI.create(s3Endpoint));
                builder.serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build());
                log.info("LocalStack S3 엔드포인트 설정: {}", s3Endpoint);
            }

            S3Client client = builder.build();
            log.info("S3Client (local) 초기화 완료");
            return client;
        } catch (Exception e) {
            log.error("S3Client (local) 초기화 실패: region={}", props.region(), e);
            throw e;
        }
    }

    /** ECS Task Role로 자동 인증하므로 키를 설정하지 않는다. */
    @Bean
    @Profile("!local")
    public S3Client s3ClientProd(S3Properties props) {
        log.info("S3Client (prod) 초기화: region={}, bucket={}", props.region(), props.bucket());
        try {
            S3Client client = S3Client.builder()
                    .region(Region.of(props.region()))
                    .credentialsProvider(DefaultCredentialsProvider.create())
                    .build();
            log.info("S3Client (prod) 초기화 완료");
            return client;
        } catch (Exception e) {
            log.error("S3Client (prod) 초기화 실패: region={}", props.region(), e);
            throw e;
        }
    }
}
