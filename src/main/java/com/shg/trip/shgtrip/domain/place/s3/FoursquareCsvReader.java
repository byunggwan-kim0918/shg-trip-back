package com.shg.trip.shgtrip.domain.place.s3;

import java.io.InputStream;
import java.util.Optional;

/** foursquare/dt={yyyy-MM-dd}/ 파티션 중 최신 날짜의 CSV를 읽는다. */
public interface FoursquareCsvReader {
    /** 파일이 없으면 Optional.empty(). */
    Optional<InputStream> readLatest();
}
