package com.shg.trip.shgtrip;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class ShgTripBackApplicationTests {
    @Test
    void contextLoads() {
    }
}
