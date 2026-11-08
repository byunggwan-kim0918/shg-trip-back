package com.shg.trip.shgtrip.domain.place.s3;

import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.io.InputStream;

public class LocalFileCsvSource implements FoursquareCsvSource {

    private static final String LOCAL_CSV_PATH = "data/foursquare-places.csv";

    @Override
    public InputStream open() throws IOException {
        return new ClassPathResource(LOCAL_CSV_PATH).getInputStream();
    }
}
