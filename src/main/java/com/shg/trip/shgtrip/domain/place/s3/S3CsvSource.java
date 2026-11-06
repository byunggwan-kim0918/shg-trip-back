package com.shg.trip.shgtrip.domain.place.s3;

import lombok.RequiredArgsConstructor;
import java.io.IOException;
import java.io.InputStream;

@RequiredArgsConstructor
public class S3CsvSource implements FoursquareCsvSource {

    private final FoursquareCsvReader csvReader;

    @Override
    public InputStream open() throws IOException {
        return csvReader.readLatest()
                .orElseThrow(() -> new IOException("S3에서 Foursquare CSV 파일을 찾을 수 없습니다."));
    }
}
