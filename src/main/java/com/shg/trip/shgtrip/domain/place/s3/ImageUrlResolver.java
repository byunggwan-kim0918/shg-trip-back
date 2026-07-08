package com.shg.trip.shgtrip.domain.place.s3;

import com.shg.trip.shgtrip.global.config.S3Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 장소 이미지의 정적(만료 없는) URL을 생성하는 단일 지점.
 *
 * <ul>
 *   <li>운영: {@code cloud.aws.s3.cloud-front-domain}이 설정되면 CloudFront 정적 URL
 *       — {@code https://{domain}/images/places/{id}.jpg}. 비공개 S3(OAC) 앞단의 CDN.</li>
 *   <li>로컬: cloud-front-domain 미설정 시 LocalStack path-style 정적 URL
 *       — {@code {endpoint}/{bucket}/images/places/{id}.jpg}. 서명 없이 GET 200(로컬 CDN 역할).</li>
 * </ul>
 *
 * presigned URL(만료·주기 갱신)을 대체한다. 저장 시점에 이 URL을 DB에 넣으면
 * 응답 DTO는 저장값을 그대로 노출하면 되므로 read-time resolver 전파가 불필요하다.
 */
@Component
public class ImageUrlResolver {

    private static final String IMAGE_KEY_PREFIX = "images/places/";
    private static final String IMAGE_EXTENSION = ".jpg";

    private final S3Properties s3Properties;

    @Value("${cloud.aws.s3.endpoint:}")
    private String s3Endpoint;

    public ImageUrlResolver(S3Properties s3Properties) {
        this.s3Properties = s3Properties;
    }

    /**
     * 장소 이미지의 정적 URL을 반환한다.
     * @param placeId Place의 DB ID
     * @return 만료 없는 이미지 URL
     */
    public String resolve(Long placeId) {
        String path = IMAGE_KEY_PREFIX + placeId + IMAGE_EXTENSION;

        // 운영: CloudFront 도메인 (비공개 S3 + OAC 앞단 CDN)
        if (StringUtils.hasText(s3Properties.cloudFrontDomain())) {
            return "https://" + s3Properties.cloudFrontDomain() + "/" + path;
        }

        // 로컬: LocalStack path-style 정적 URL
        // fail-fast: cloudFrontDomain·endpoint 둘 다 없으면 스킴/호스트 없는 상대경로("/bucket/...")를
        // DB에 영구 저장하게 된다. 이 조용한 손상을 막기 위해 명시적으로 실패시킨다.
        // (운영에서 CLOUDFRONT_DOMAIN env 미주입 시 이 경로로 빠지는 것을 차단)
        if (!StringUtils.hasText(s3Endpoint)) {
            throw new IllegalStateException(
                    "이미지 URL을 생성할 수 없습니다: cloud.aws.s3.cloud-front-domain(CLOUDFRONT_DOMAIN)과 "
                            + "cloud.aws.s3.endpoint가 모두 비어 있습니다. 운영에서는 CLOUDFRONT_DOMAIN을 주입하세요.");
        }
        return s3Endpoint + "/" + s3Properties.bucket() + "/" + path;
    }
}
