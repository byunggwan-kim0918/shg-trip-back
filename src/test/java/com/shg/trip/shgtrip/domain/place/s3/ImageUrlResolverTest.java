package com.shg.trip.shgtrip.domain.place.s3;

import com.shg.trip.shgtrip.global.config.S3Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageUrlResolverTest {

    private ImageUrlResolver resolverWith(String cloudFrontDomain, String endpoint) {
        S3Properties props = new S3Properties(
                "ap-northeast-2", "shgtrip-data", "key", "secret", cloudFrontDomain);
        ImageUrlResolver resolver = new ImageUrlResolver(props);
        ReflectionTestUtils.setField(resolver, "s3Endpoint", endpoint);
        return resolver;
    }

    @Test
    @DisplayName("cloudFrontDomain이 있으면 CloudFront https 정적 URL을 반환한다")
    void resolvesCloudFrontUrlWhenDomainPresent() {
        ImageUrlResolver resolver = resolverWith("d123abc.cloudfront.net", "http://localhost:4566");

        String url = resolver.resolve(42L);

        assertThat(url).isEqualTo("https://d123abc.cloudfront.net/images/places/42.jpg");
    }

    @Test
    @DisplayName("cloudFrontDomain이 없으면 LocalStack path-style 정적 URL을 반환한다")
    void resolvesPathStyleUrlWhenDomainAbsent() {
        ImageUrlResolver resolver = resolverWith("", "http://localhost:4566");

        String url = resolver.resolve(42L);

        assertThat(url).isEqualTo("http://localhost:4566/shgtrip-data/images/places/42.jpg");
    }

    @Test
    @DisplayName("cloudFrontDomain이 null이어도 path-style로 폴백한다")
    void resolvesPathStyleUrlWhenDomainNull() {
        ImageUrlResolver resolver = resolverWith(null, "http://localhost:4566");

        String url = resolver.resolve(7L);

        assertThat(url).isEqualTo("http://localhost:4566/shgtrip-data/images/places/7.jpg");
    }

    @Test
    @DisplayName("cloudFrontDomain이 있으면 endpoint가 없어도 CloudFront URL을 반환한다 (운영 정상 경로)")
    void resolvesCloudFrontUrlEvenWithoutEndpoint() {
        ImageUrlResolver resolver = resolverWith("d123abc.cloudfront.net", "");

        String url = resolver.resolve(42L);

        assertThat(url).isEqualTo("https://d123abc.cloudfront.net/images/places/42.jpg");
    }

    @Test
    @DisplayName("cloudFrontDomain·endpoint 둘 다 없으면 예외를 던진다 (운영 오설정 fail-fast)")
    void throwsWhenBothDomainAndEndpointMissing() {
        ImageUrlResolver resolver = resolverWith("", "");

        assertThatThrownBy(() -> resolver.resolve(42L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLOUDFRONT_DOMAIN");
    }

    @Test
    @DisplayName("cloudFrontDomain·endpoint 둘 다 null이어도 예외를 던진다")
    void throwsWhenBothDomainAndEndpointNull() {
        ImageUrlResolver resolver = resolverWith(null, null);

        assertThatThrownBy(() -> resolver.resolve(42L))
                .isInstanceOf(IllegalStateException.class);
    }
}
