package com.shg.trip.shgtrip.domain.place.s3;

import java.io.IOException;
import java.io.InputStream;

/**
 * Foursquare CSV 데이터 소스 전략 인터페이스.
 * 로컬 파일 또는 S3에서 CSV를 읽어 InputStream으로 제공한다.
 */
public interface FoursquareCsvSource {
    /**
     * CSV 파일의 InputStream을 열어 반환한다.
     * @return CSV InputStream
     * @throws IOException 파일 읽기 실패 시
     */
    InputStream open() throws IOException;

    /**
     * 소스 경로를 반환한다 (이력 기록용). 구현체가 재정의 가능.
     */
    default String sourcePath() {
        return getClass().getSimpleName();
    }
}
