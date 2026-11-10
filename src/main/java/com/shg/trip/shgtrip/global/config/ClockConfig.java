package com.shg.trip.shgtrip.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/** 운영 JVM 기본 시간대가 UTC라 KST 자정~09시에 "오늘"이 하루 밀린다. 날짜를 해석하는 곳은 이 시계를 쓴다. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
