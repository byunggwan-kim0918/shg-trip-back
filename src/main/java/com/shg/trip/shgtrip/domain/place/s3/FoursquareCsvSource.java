package com.shg.trip.shgtrip.domain.place.s3;

import java.io.IOException;
import java.io.InputStream;

public interface FoursquareCsvSource {
    InputStream open() throws IOException;

    /** 시딩 이력에 남길 소스 경로. */
    default String sourcePath() {
        return getClass().getSimpleName();
    }
}
