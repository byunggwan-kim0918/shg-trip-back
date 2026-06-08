package com.shg.trip.shgtrip.domain.place.s3;

import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.io.InputStream;

/**
 * ClassPath의 로컬 CSV 파일에서 InputStream을 제공하는 구현체.
 * batch.foursquare.source=local 설정 시 사용된다.
 */
public class LocalFileCsvSource implements FoursquareCsvSource {

    private static final String LOCAL_CSV_PATH = "data/foursquare-places.csv";

    @Override
    public InputStream open() throws IOException {
        return new ClassPathResource(LOCAL_CSV_PATH).getInputStream();
    }
}
