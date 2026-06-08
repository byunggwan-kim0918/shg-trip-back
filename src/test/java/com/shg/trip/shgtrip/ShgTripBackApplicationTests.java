package com.shg.trip.shgtrip;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class ShgTripBackApplicationTests {

    @MockBean
    S3Client s3Client;

    @Test
    void contextLoads() {
    }
}
