package com.shg.trip.shgtrip.domain.place.catalog;

import com.shg.trip.shgtrip.global.config.S3Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 장소 카탈로그 빈. {@code place.catalog.path}(로컬 폴더)를 쓰고, {@code place.catalog.s3-keys}(데이터 버킷 키, 쉼표 구분)가
 * 있으면 시작할 때 그 파일들을 임시 폴더로 받아 연다. 운영 이미지에는 카탈로그 파일이 들어 있지 않다.
 */
@Slf4j
@Configuration
public class PlaceCatalogConfig {

    @Bean
    public PlaceCatalog placeCatalog(@Value("${place.catalog.path:}") String path,
                                     @Value("${place.catalog.s3-keys:}") String s3Keys,
                                     ObjectProvider<S3Client> s3, ObjectProvider<S3Properties> s3Properties) {
        List<String> keys = Arrays.stream(s3Keys.split(",")).map(String::trim).filter(k -> !k.isEmpty()).toList();
        if (keys.isEmpty()) return new PlaceCatalog(path);
        // 카탈로그는 DB에 없는 장소를 보충할 뿐이라, 받지 못하면 꺼진 채로 띄우고 생성은 DB 장소로 계속한다.
        try {
            return new PlaceCatalog(download(s3.getObject(), s3Properties.getObject().bucket(), keys).toString());
        } catch (RuntimeException | IOException e) {
            log.error("장소 카탈로그를 받지 못해 끈 채로 시작한다: keys={}", keys, e);
            return new PlaceCatalog("");
        }
    }

    static Path download(S3Client s3, String bucket, List<String> keys) throws IOException {
        Path dir = Files.createTempDirectory("place-catalog");
        for (String key : keys) {
            Path target = dir.resolve(Path.of(key).getFileName().toString());
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), target);
            log.info("장소 카탈로그 받음: s3://{}/{} ({} bytes)", bucket, key, Files.size(target));
        }
        return dir;
    }
}
