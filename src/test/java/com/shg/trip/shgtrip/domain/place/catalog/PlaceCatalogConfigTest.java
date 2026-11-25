package com.shg.trip.shgtrip.domain.place.catalog;

import com.shg.trip.shgtrip.global.config.S3Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PlaceCatalogConfigTest {

    private final S3Client s3 = mock(S3Client.class);
    private final PlaceCatalogConfig config = new PlaceCatalogConfig();

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        given(p.getObject()).willReturn(value);
        return p;
    }

    private PlaceCatalog build(String path, String keys) {
        return config.placeCatalog(path, keys, provider(s3),
                provider(new S3Properties("ap-northeast-2", "bucket", null, null, null)));
    }

    @Test
    @DisplayName("S3 키가 없으면 로컬 경로를 그대로 쓰고 S3는 부르지 않는다")
    void noKeysUsesLocalPath() throws Exception {
        try (PlaceCatalog catalog = build("", "")) {
            assertThat(catalog.enabled()).isFalse();
        }
        verify(s3, never()).getObject(any(GetObjectRequest.class), any(Path.class));
    }

    @Test
    @DisplayName("S3에서 받은 파일로 카탈로그를 연다")
    void downloadedFileOpensCatalog() throws Exception {
        given(s3.getObject(any(GetObjectRequest.class), any(Path.class))).willAnswer(inv -> {
            Path target = inv.getArgument(1);
            try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
                s.execute("COPY (SELECT '円山公園' AS name) TO '%s' (FORMAT parquet)".formatted(target));
            }
            return null;
        });

        try (PlaceCatalog catalog = build("", "catalog/jp.parquet")) {
            assertThat(catalog.enabled()).isTrue();
        }
    }

    @Test
    @DisplayName("받지 못하면 카탈로그를 끈 채로 만든다")
    void downloadFailureDisablesCatalog() throws Exception {
        given(s3.getObject(any(GetObjectRequest.class), any(Path.class)))
                .willThrow(SdkClientException.create("no route"));

        try (PlaceCatalog catalog = build("", "catalog/jp.parquet")) {
            assertThat(catalog.enabled()).isFalse();
        }
    }
}
