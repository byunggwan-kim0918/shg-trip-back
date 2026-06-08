package com.shg.trip.shgtrip.domain.place.s3;

import java.io.InputStream;
import java.util.Optional;

/**
 * S3 버킷에서 최신 Foursquare CSV 파일의 InputStream을 읽어오는 인터페이스.
 * foursquare/dt={yyyy-MM-dd}/ 파티션 구조에서 최신 날짜를 선택하여 반환한다.
 */
public interface FoursquareCsvReader {
    /**
     * S3에서 최신 Foursquare CSV 파일의 InputStream을 반환한다.
     * 파일이 없으면 Optional.empty() 반환.
     * @return CSV 파일의 InputStream, 없으면 Optional.empty()
     */
    Optional<InputStream> readLatest();
}
