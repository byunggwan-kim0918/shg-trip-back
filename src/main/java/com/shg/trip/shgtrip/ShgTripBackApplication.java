package com.shg.trip.shgtrip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableRetry
public class ShgTripBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShgTripBackApplication.class, args);
    }

}
