package com.shg.trip.shgtrip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ShgTripBackApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShgTripBackApplication.class, args);
    }

}
